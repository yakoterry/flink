/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.table.planner.plan.nodes.physical.stream

import org.apache.flink.api.dag.Transformation
import org.apache.flink.configuration.Configuration
import org.apache.flink.core.memory.ManagedMemoryUseCase
import org.apache.flink.streaming.api.operators.OneInputStreamOperator
import org.apache.flink.streaming.api.transformations.OneInputTransformation
import org.apache.flink.table.api.TableException
import org.apache.flink.table.data.RowData
import org.apache.flink.table.functions.python.PythonFunctionInfo
import org.apache.flink.table.planner.calcite.FlinkRelBuilder.PlannerNamedWindowProperty
import org.apache.flink.table.planner.calcite.FlinkTypeFactory
import org.apache.flink.table.planner.delegation.StreamPlanner
import org.apache.flink.table.planner.expressions.{PlannerProctimeAttribute, PlannerRowtimeAttribute, PlannerWindowEnd, PlannerWindowStart}
import org.apache.flink.table.planner.plan.logical.{LogicalWindow, SlidingGroupWindow, TumblingGroupWindow}
import org.apache.flink.table.planner.plan.nodes.exec.common.CommonExecPythonAggregate
import org.apache.flink.table.planner.plan.nodes.physical.stream.StreamExecPythonGroupWindowAggregate.ARROW_STREAM_PYTHON_GROUP_WINDOW_AGGREGATE_FUNCTION_OPERATOR_NAME
import org.apache.flink.table.planner.plan.utils.AggregateUtil._
import org.apache.flink.table.planner.plan.utils.{KeySelectorUtil, WindowEmitStrategy}
import org.apache.flink.table.runtime.operators.window.assigners._
import org.apache.flink.table.runtime.operators.window.triggers.{ElementTriggers, EventTimeTriggers, ProcessingTimeTriggers, Trigger}
import org.apache.flink.table.runtime.typeutils.InternalTypeInfo
import org.apache.flink.table.types.logical.RowType

import org.apache.calcite.plan.{RelOptCluster, RelTraitSet}
import org.apache.calcite.rel.RelNode
import org.apache.calcite.rel.`type`.RelDataType
import org.apache.calcite.rel.core.AggregateCall

/**
  * Stream physical RelNode for group widow aggregate (Python user defined aggregate function).
  */
class StreamExecPythonGroupWindowAggregate(
    cluster: RelOptCluster,
    traitSet: RelTraitSet,
    inputRel: RelNode,
    outputRowType: RelDataType,
    inputRowType: RelDataType,
    grouping: Array[Int],
    aggCalls: Seq[AggregateCall],
    window: LogicalWindow,
    namedProperties: Seq[PlannerNamedWindowProperty],
    inputTimeFieldIndex: Int,
    emitStrategy: WindowEmitStrategy)
  extends StreamExecGroupWindowAggregateBase(
    cluster,
    traitSet,
    inputRel,
    outputRowType,
    inputRowType,
    grouping,
    aggCalls,
    window,
    namedProperties,
    inputTimeFieldIndex,
    emitStrategy,
    "PythonAggregate")
  with CommonExecPythonAggregate {

  override def copy(traitSet: RelTraitSet, inputs: java.util.List[RelNode]): RelNode = {
    new StreamExecPythonGroupWindowAggregate(
      cluster,
      traitSet,
      inputs.get(0),
      outputRowType,
      inputRowType,
      grouping,
      aggCalls,
      window,
      namedProperties,
      inputTimeFieldIndex,
      emitStrategy)
  }

  override def translateToPlanInternal(planner: StreamPlanner): Transformation[RowData] = {
    val config = planner.getTableConfig

    val inputTransform = getInputNodes.get(0).translateToPlan(planner)
      .asInstanceOf[Transformation[RowData]]

    val inputRowTypeInfo = inputTransform.getOutputType.asInstanceOf[InternalTypeInfo[RowData]]

    val outputType = FlinkTypeFactory.toLogicalRowType(getRowType)
    val inputType = FlinkTypeFactory.toLogicalRowType(inputRowType)

    val isCountWindow = window match {
      case TumblingGroupWindow(_, _, size) if hasRowIntervalType(size) => true
      case SlidingGroupWindow(_, _, size, _) if hasRowIntervalType(size) => true
      case _ => false
    }

    if (isCountWindow && grouping.length > 0 && config.getMinIdleStateRetentionTime < 0) {
      LOG.warn(
        "No state retention interval configured for a query which accumulates state. " +
          "Please provide a query configuration with valid retention interval to prevent " +
          "excessive state size. You may specify a retention time of 0 to not clean up the state.")
    }

    val timeIdx = if (isRowtimeAttribute(window.timeAttribute)) {
      if (inputTimeFieldIndex < 0) {
        throw new TableException(
          s"Group window PythonAggregate must defined on a time attribute, " +
            "but the time attribute can't be found.\n" +
            "This should never happen. Please file an issue."
          )
      }
      inputTimeFieldIndex
    } else {
      -1
    }

    val (windowAssigner, trigger) = generateWindowAssignerAndTrigger()
    val ret = createPythonStreamWindowGroupOneInputTransformation(
      inputTransform,
      inputType,
      outputType,
      timeIdx,
      windowAssigner,
      trigger,
      emitStrategy.getAllowLateness,
      getConfig(planner.getExecEnv, planner.getTableConfig))

    if (inputsContainSingleton()) {
      ret.setParallelism(1)
      ret.setMaxParallelism(1)
    }

    val selector = KeySelectorUtil.getRowDataSelector(grouping, inputRowTypeInfo)

    // set KeyType and Selector for state
    ret.setStateKeySelector(selector)
    ret.setStateKeyType(selector.getProducedType)

    if (isPythonWorkerUsingManagedMemory(planner.getTableConfig.getConfiguration)) {
      ret.declareManagedMemoryUseCaseAtSlotScope(ManagedMemoryUseCase.PYTHON)
    }
    ret
  }

  private[this] def generateWindowAssignerAndTrigger(): (WindowAssigner[_], Trigger[_]) = {
    window match {
      case TumblingGroupWindow(_, timeField, size)
        if isProctimeAttribute(timeField) && hasTimeIntervalType(size) =>
        (TumblingWindowAssigner.of(toDuration(size)).withProcessingTime(),
          ProcessingTimeTriggers.afterEndOfWindow())

      case TumblingGroupWindow(_, timeField, size)
        if isRowtimeAttribute(timeField) && hasTimeIntervalType(size) =>
        (TumblingWindowAssigner.of(toDuration(size)).withEventTime(),
          EventTimeTriggers.afterEndOfWindow())

      case TumblingGroupWindow(_, timeField, size)
        if isProctimeAttribute(timeField) && hasRowIntervalType(size) =>
        (CountTumblingWindowAssigner.of(toLong(size)),
          ElementTriggers.count(toLong(size)))

      case TumblingGroupWindow(_, _, _) =>
        // TODO: EventTimeTumblingGroupWindow should sort the stream on event time
        // before applying the  windowing logic. Otherwise, this would be the same as a
        // ProcessingTimeTumblingGroupWindow
        throw new UnsupportedOperationException(
          "Event-time grouping windows on row intervals are currently not supported.")

      case SlidingGroupWindow(_, timeField, size, slide)
        if isProctimeAttribute(timeField) && hasTimeIntervalType(size) =>
        (SlidingWindowAssigner.of(toDuration(size), toDuration(slide)),
          ProcessingTimeTriggers.afterEndOfWindow())

      case SlidingGroupWindow(_, timeField, size, slide)
        if isRowtimeAttribute(timeField) && hasTimeIntervalType(size) =>
        (SlidingWindowAssigner.of(toDuration(size), toDuration(slide)),
          EventTimeTriggers.afterEndOfWindow())

      case SlidingGroupWindow(_, timeField, size, slide)
        if isProctimeAttribute(timeField) && hasRowIntervalType(size) =>
        (CountSlidingWindowAssigner.of(toLong(size), toLong(slide)),
          ElementTriggers.count(toLong(size)))

      case SlidingGroupWindow(_, _, _, _) =>
        // TODO: EventTimeTumblingGroupWindow should sort the stream on event time
        // before applying the  windowing logic. Otherwise, this would be the same as a
        // ProcessingTimeTumblingGroupWindow
        throw new UnsupportedOperationException(
          "Event-time grouping windows on row intervals are currently not supported.")
    }
  }

  private[this] def createPythonStreamWindowGroupOneInputTransformation(
      inputTransform: Transformation[RowData],
      inputRowType: RowType,
      outputRowType: RowType,
      inputTimeFieldIndex: Int,
      windowAssigner: WindowAssigner[_],
      trigger: Trigger[_],
      allowance: Long,
      config: Configuration): OneInputTransformation[RowData, RowData] = {

    val namePropertyTypeArray = namedProperties
      .map {
        case PlannerNamedWindowProperty(_, p) => p match {
          case PlannerWindowStart(_) => 0
          case PlannerWindowEnd(_) => 1
          case PlannerRowtimeAttribute(_) => 2
          case PlannerProctimeAttribute(_) => 3
        }
      }.toArray

    val (pythonUdafInputOffsets, pythonFunctionInfos) =
      extractPythonAggregateFunctionInfosFromAggregateCall(aggCalls)
    val pythonOperator = getPythonStreamGroupWindowAggregateFunctionOperator(
      config,
      inputRowType,
      outputRowType,
      windowAssigner,
      trigger,
      allowance,
      inputTimeFieldIndex,
      namePropertyTypeArray,
      pythonUdafInputOffsets,
      pythonFunctionInfos)

    new OneInputTransformation(
      inputTransform,
      "StreamExecPythonGroupWindowAggregate",
      pythonOperator,
      InternalTypeInfo.of(outputRowType),
      inputTransform.getParallelism)
  }

  private[this] def getPythonStreamGroupWindowAggregateFunctionOperator(
      config: Configuration,
      inputRowType: RowType,
      outputRowType: RowType,
      windowAssigner: WindowAssigner[_],
      trigger: Trigger[_],
      allowance: Long,
      inputTimeFieldIndex: Int,
      namedProperties: Array[Int],
      udafInputOffsets: Array[Int],
      pythonFunctionInfos: Array[PythonFunctionInfo]): OneInputStreamOperator[RowData, RowData] = {
    val clazz = loadClass(ARROW_STREAM_PYTHON_GROUP_WINDOW_AGGREGATE_FUNCTION_OPERATOR_NAME)

    val ctor = clazz.getConstructor(
      classOf[Configuration],
      classOf[Array[PythonFunctionInfo]],
      classOf[RowType],
      classOf[RowType],
      classOf[Int],
      classOf[WindowAssigner[_]],
      classOf[Trigger[_]],
      classOf[Long],
      classOf[Array[Int]],
      classOf[Array[Int]],
      classOf[Array[Int]])

    ctor.newInstance(
      config,
      pythonFunctionInfos,
      inputRowType,
      outputRowType,
      Integer.valueOf(inputTimeFieldIndex),
      windowAssigner,
      trigger,
      java.lang.Long.valueOf(allowance),
      namedProperties,
      grouping,
      udafInputOffsets)
      .asInstanceOf[OneInputStreamOperator[RowData, RowData]]
  }
}

object StreamExecPythonGroupWindowAggregate {
  val ARROW_STREAM_PYTHON_GROUP_WINDOW_AGGREGATE_FUNCTION_OPERATOR_NAME: String =
    "org.apache.flink.table.runtime.operators.python.aggregate.arrow.stream." +
      "StreamArrowPythonGroupWindowAggregateFunctionOperator"
}
