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

package org.apache.spark.eventhubs.client

import com.microsoft.azure.eventhubs._
import org.apache.spark.{ SparkEnv, TaskContext }
import org.apache.spark.eventhubs.{ EventHubsConf, NameAndPartition, SequenceNumber }
import org.apache.spark.internal.Logging

import scala.util.{ Failure, Success, Try }

private[spark] trait CachedReceiver {
  private[eventhubs] def receive(ehConf: EventHubsConf,
                                 nAndP: NameAndPartition,
                                 requestSeqNo: SequenceNumber,
                                 batchSize: Int): EventData
}

/**
 * An Event Hubs receiver instance that is cached on a Spark Executor to be
 * reused across batches. In Structured Streaming and Spark Streaming,
 * partitions are generated on the same executors across batches, so that
 * receivers can be cached are reused for maximum efficiency. Receiver caching
 * allows the underlying [[PartitionReceiver]]s to prefetch [[EventData]] from
 * the service before DataFrames or RDDs are generated by Spark.
 *
 * This class creates and maintains an AMQP link with the Event Hubs service.
 * On creation, an [[EventHubClient]] is borrowed from the [[ClientConnectionPool]].
 * Then a [[PartitionReceiver]] is created on top of the borrowed connection with the
 * [[NameAndPartition]].
 *
 * @param ehConf the [[EventHubsConf]] which contains the connection string used to connect to Event Hubs
 * @param nAndP the Event Hub name and partition that the receiver is connected to.
 */
private[client] class CachedEventHubsReceiver private (ehConf: EventHubsConf,
                                                       nAndP: NameAndPartition)
    extends Logging {

  import org.apache.spark.eventhubs._

  private lazy val client: EventHubClient = ClientConnectionPool.borrowClient(ehConf)

  private def createReceiver(requestSeqNo: SequenceNumber): Unit = {
    logInfo(s"creating receiver for Event Hub ${nAndP.ehName} on partition ${nAndP.partitionId}")
    val consumerGroup = ehConf.consumerGroup.getOrElse(DefaultConsumerGroup)
    val receiverOptions = new ReceiverOptions
    receiverOptions.setReceiverRuntimeMetricEnabled(false)
    receiverOptions.setIdentifier(
      s"spark-${SparkEnv.get.executorId}-${TaskContext.get.taskAttemptId}")
    _receiver = client.createEpochReceiverSync(
      consumerGroup,
      nAndP.partitionId.toString,
      EventPosition.fromSequenceNumber(requestSeqNo).convert,
      DefaultEpoch,
      receiverOptions)
    _receiver.setPrefetchCount(DefaultPrefetchCount)
  }

  private def closeReceiver(): Unit = {
    Try(_receiver.closeSync()) match {
      case Success(_) => _receiver = null
      case Failure(e) =>
        logInfo("closeSync failed in cached receiver.", e)
        _receiver = null
    }
  }

  private var _receiver: PartitionReceiver = _
  private def receiver: PartitionReceiver = {
    if (_receiver == null) {
      throw new IllegalStateException(s"No receiver for $nAndP")
    }
    _receiver
  }

  private def errWrongSeqNo(requestSeqNo: SequenceNumber, receivedSeqNo: SequenceNumber): String =
    s"requestSeqNo $requestSeqNo does not match the received sequence number $receivedSeqNo"

  private def receive(requestSeqNo: SequenceNumber, batchSize: Int): EventData = {
    def receiveOneEvent: EventData = {
      var event: EventData = null
      var i: java.lang.Iterable[EventData] = null
      while (i == null) {
        i = try {
          receiver.receiveSync(1)
        } catch {
          case r: ReceiverDisconnectedException =>
            throw new Exception(
              "You are likely running multiple Spark jobs with the same consumer group. " +
                "For each Spark job, please create and use a unique consumer group to avoid this issue.",
              r
            )
        }
      }
      event = i.iterator.next
      event
    }

    var event: EventData = receiveOneEvent
    if (requestSeqNo != event.getSystemProperties.getSequenceNumber) {
      logWarning(
        s"$requestSeqNo did not match ${event.getSystemProperties.getSequenceNumber}." +
          s"Recreating receiver for $nAndP")
      closeReceiver()
      createReceiver(requestSeqNo)
      event = receiveOneEvent
      assert(requestSeqNo == event.getSystemProperties.getSequenceNumber,
             errWrongSeqNo(requestSeqNo, event.getSystemProperties.getSequenceNumber))
    }
    val newPrefetchCount = if (batchSize < PrefetchCountMinimum) PrefetchCountMinimum else batchSize
    receiver.setPrefetchCount(newPrefetchCount)
    event
  }
}

/**
 * A companion object to the [[CachedEventHubsReceiver]]. This companion object
 * serves as a singleton which carries all the cached receivers on a given
 * Spark executor.
 */
private[spark] object CachedEventHubsReceiver extends CachedReceiver with Logging {

  type MutableMap[A, B] = scala.collection.mutable.HashMap[A, B]

  private[this] val receivers = new MutableMap[String, CachedEventHubsReceiver]()

  private def key(ehConf: EventHubsConf, nAndP: NameAndPartition): String = {
    (ehConf.connectionString + ehConf.consumerGroup + nAndP.partitionId).toLowerCase
  }

  private[eventhubs] override def receive(ehConf: EventHubsConf,
                                          nAndP: NameAndPartition,
                                          requestSeqNo: SequenceNumber,
                                          batchSize: Int): EventData = {
    var receiver: CachedEventHubsReceiver = null
    receivers.synchronized {
      receiver = receivers.getOrElseUpdate(key(ehConf, nAndP), {
        CachedEventHubsReceiver(ehConf, nAndP, requestSeqNo)
      })
    }
    receiver.receive(requestSeqNo, batchSize)
  }

  def apply(ehConf: EventHubsConf,
            nAndP: NameAndPartition,
            startSeqNo: SequenceNumber): CachedEventHubsReceiver = {
    val cr = new CachedEventHubsReceiver(ehConf, nAndP)
    cr.createReceiver(startSeqNo)
    cr
  }
}
