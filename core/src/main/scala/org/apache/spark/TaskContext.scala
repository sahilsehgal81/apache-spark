/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark

import scala.collection.mutable.ArrayBuffer

import org.apache.spark.annotation.DeveloperApi
import org.apache.spark.executor.TaskMetrics
import org.apache.spark.util.TaskCompletionListener


/**
 * :: DeveloperApi ::
 * Contextual information about a task which can be read or mutated during execution.
 *
 * @param stageId stage id
 * @param partitionId index of the partition
 * @param attemptId the number of attempts to execute this task
 * @param runningLocally whether the task is running locally in the driver JVM
 * @param taskMetrics performance metrics of the task
 */
@DeveloperApi
class TaskContext(
    val stageId: Int,
    val partitionId: Int,
    val attemptId: Long,
    val runningLocally: Boolean = false,
    private[spark] val taskMetrics: TaskMetrics = TaskMetrics.empty)
  extends Serializable {

  @deprecated("use partitionId", "0.8.1")
  def splitId = partitionId

  // List of callback functions to execute when the task completes.
  @transient private val onCompleteCallbacks = new ArrayBuffer[TaskCompletionListener]

  // Whether the corresponding task has been killed.
  @volatile private var interrupted: Boolean = false

  // Whether the task has completed.
  @volatile private var completed: Boolean = false

  /** Checks whether the task has completed. */
  def isCompleted: Boolean = completed

  /** Checks whether the task has been killed. */
  def isInterrupted: Boolean = interrupted

  // TODO: Also track whether the task has completed successfully or with exception.

  /**
   * Add a (Java friendly) listener to be executed on task completion.
   * This will be called in all situation - success, failure, or cancellation.
   *
   * An example use is for HadoopRDD to register a callback to close the input stream.
   */
  def addTaskCompletionListener(listener: TaskCompletionListener): this.type = {
    onCompleteCallbacks += listener
    this
  }

  /**
   * Add a listener in the form of a Scala closure to be executed on task completion.
   * This will be called in all situation - success, failure, or cancellation.
   *
   * An example use is for HadoopRDD to register a callback to close the input stream.
   */
  def addTaskCompletionListener(f: TaskContext => Unit): this.type = {
    onCompleteCallbacks += new TaskCompletionListener {
      override def onTaskCompletion(context: TaskContext): Unit = f(context)
    }
    this
  }

  /**
   * Add a callback function to be executed on task completion. An example use
   * is for HadoopRDD to register a callback to close the input stream.
   * Will be called in any situation - success, failure, or cancellation.
   * @param f Callback function.
   */
  @deprecated("use addTaskCompletionListener", "1.1.0")
  def addOnCompleteCallback(f: () => Unit) {
    onCompleteCallbacks += new TaskCompletionListener {
      override def onTaskCompletion(context: TaskContext): Unit = f()
    }
  }

  /** Marks the task as completed and triggers the listeners. */
  private[spark] def markTaskCompleted(): Unit = {
    completed = true
    // Process complete callbacks in the reverse order of registration
    onCompleteCallbacks.reverse.foreach { _.onTaskCompletion(this) }
  }

  /** Marks the task for interruption, i.e. cancellation. */
  private[spark] def markInterrupted(): Unit = {
    interrupted = true
  }
}
