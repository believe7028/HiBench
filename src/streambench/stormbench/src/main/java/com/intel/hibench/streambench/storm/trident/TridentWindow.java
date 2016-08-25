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
package com.intel.hibench.streambench.storm.trident;

import com.intel.hibench.streambench.common.metrics.KafkaReporter;
import com.intel.hibench.streambench.common.metrics.LatencyReporter;
import com.intel.hibench.streambench.storm.spout.KafkaSpoutFactory;
import com.intel.hibench.streambench.storm.topologies.SingleTridentSpoutTops;
import com.intel.hibench.streambench.storm.trident.functions.Parser;
import com.intel.hibench.streambench.storm.util.StormBenchConfig;
import org.apache.storm.kafka.trident.OpaqueTridentKafkaSpout;
import org.apache.storm.topology.base.BaseWindowedBolt;
import org.apache.storm.trident.TridentTopology;
import org.apache.storm.trident.operation.BaseAggregator;
import org.apache.storm.trident.operation.TridentCollector;
import org.apache.storm.trident.operation.TridentOperationContext;
import org.apache.storm.trident.tuple.TridentTuple;
import org.apache.storm.trident.windowing.InMemoryWindowsStoreFactory;
import org.apache.storm.tuple.Fields;
import org.apache.storm.tuple.Values;

import java.util.Map;
import java.util.concurrent.TimeUnit;

public class TridentWindow extends SingleTridentSpoutTops {

  public TridentWindow(StormBenchConfig config) {
    super(config);
  }

  @Override
  public TridentTopology createTopology() {
    OpaqueTridentKafkaSpout spout = KafkaSpoutFactory.getTridentSpout(config);

    TridentTopology topology = new TridentTopology();
    topology.newStream("bg0", spout)
        .each(spout.getOutputFields(), new Parser(), new Fields("ip", "time"))
        .parallelismHint(config.spoutThreads)
        .groupBy(new Fields("ip")).toStream()
        .slidingWindow(new BaseWindowedBolt.Duration((int) config.windowDuration, TimeUnit.MILLISECONDS),
            new BaseWindowedBolt.Duration((int) config.windowSlideStep, TimeUnit.MILLISECONDS),
            new InMemoryWindowsStoreFactory(),
            new Fields("ip", "time"), new Count(config), new Fields("ip", "count"))
        .parallelismHint(config.boltThreads);
    return topology;
  }

  private static class Count extends BaseAggregator<Count.State> {

    private final StormBenchConfig config;
    private LatencyReporter reporter = null;

    Count(StormBenchConfig config) {
      this.config = config;
    }

    static class State {
      String ip;
      long minTime = Long.MAX_VALUE;
      long count = 0L;
    }

    @Override
    public void prepare(Map conf, TridentOperationContext context) {
      this.reporter = new KafkaReporter(config.reporterTopic, config.brokerList);
    }

    @Override
    public State init(Object batchId, TridentCollector tridentCollector) {
      return new State();
    }

    @Override
    public void aggregate(State state, TridentTuple tridentTuple, TridentCollector tridentCollector) {
      state.ip = tridentTuple.getString(0);
      state.count++;
      state.minTime = Math.min(tridentTuple.getLong(1), state.minTime);
    }

    @Override
    public void complete(State state, TridentCollector tridentCollector) {
      tridentCollector.emit(new Values(state.ip, state.count));
      for (int i = 0; i < state.count; i++) {
        reporter.report(state.minTime, System.currentTimeMillis());
      }
    }
  }
}
