/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.solr.util.tracing;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import io.opentracing.mock.MockSpan;
import io.opentracing.mock.MockTracer;
import io.opentracing.util.GlobalTracer;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.impl.CloudSolrClient;
import org.apache.solr.client.solrj.request.CollectionAdminRequest;
import org.apache.solr.cloud.SolrCloudTestCase;
import org.apache.solr.util.LogLevel;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;


@LogLevel("org.apache.solr.core.TracerConfigurator=trace")
public class TestDistributedTracing extends SolrCloudTestCase {
  private static final String COLLECTION = "collection1";

  static MockTracer tracer;

  @BeforeClass
  public static void beforeTest() throws Exception {
    tracer = new MockTracer();
    assertTrue(GlobalTracer.registerIfAbsent(tracer));

    configureCluster(4)
        .addConfig("config", TEST_PATH().resolve("configsets").resolve("cloud-minimal").resolve("conf"))
        .configure();
    CollectionAdminRequest
        .createCollection(COLLECTION, "config", 2, 2)
        .setPerReplicaState(SolrCloudTestCase.USE_PER_REPLICA_STATE)
        .process(cluster.getSolrClient());
    cluster.waitForActiveCollection(COLLECTION, 2, 4);
  }

  @AfterClass
  public static void afterTest() {
    tracer = null;
  }

  @Test
  public void test() throws IOException, SolrServerException, TimeoutException, InterruptedException {
    // TODO it would be clearer if we could compare the complete Span tree between reality
    //   and what we assert it looks like in a structured visual way.

    CloudSolrClient cloudClient = cluster.getSolrClient();
    List<MockSpan> allSpans = tracer.finishedSpans();

    cloudClient.add(COLLECTION, sdoc("id", "1"));
    List<MockSpan> finishedSpans = getRecentSpans(allSpans);
    finishedSpans.removeIf(x ->
        !x.tags().get("http.url").toString().endsWith("/update"));
    assertEquals(2, finishedSpans.size());
    assertOneSpanIsChildOfAnother(finishedSpans);

    cloudClient.add(COLLECTION, sdoc("id", "2"));
    finishedSpans = getRecentSpans(allSpans);
    finishedSpans.removeIf(x ->
        !x.tags().get("http.url").toString().endsWith("/update"));
    assertEquals(2, finishedSpans.size());
    assertOneSpanIsChildOfAnother(finishedSpans);

    cloudClient.add(COLLECTION, sdoc("id", "3"));
    cloudClient.add(COLLECTION, sdoc("id", "4"));
    cloudClient.commit(COLLECTION);

    getRecentSpans(allSpans);
    cloudClient.query(COLLECTION, new SolrQuery("*:*"));
    finishedSpans = getRecentSpans(allSpans);
    finishedSpans.removeIf(x ->
        !x.tags().get("http.url").toString().endsWith("/select"));
    // one from client to server, 2 for execute query, 2 for fetching documents
    assertEquals(5, finishedSpans.size());
    assertEquals(1, finishedSpans.stream().filter(s -> s.parentId() == 0).count());
    long parentId = finishedSpans.stream()
        .filter(s -> s.parentId() == 0)
        .collect(Collectors.toList())
        .get(0).context().spanId();
    for (MockSpan span: finishedSpans) {
      if (span.parentId() != 0 && parentId != span.parentId()) {
        fail("All spans must belong to single span, but:"+finishedSpans);
      }
    }
  }

  private void assertOneSpanIsChildOfAnother(List<MockSpan> finishedSpans) {
    MockSpan child = finishedSpans.get(0);
    MockSpan parent = finishedSpans.get(1);
    if (child.parentId() == 0) {
      MockSpan temp = parent;
      parent = child;
      child = temp;
    }

    assertEquals(child.parentId(), parent.context().spanId());
  }

  private List<MockSpan> getRecentSpans(List<MockSpan> allSpans) {
    List<MockSpan> result = new ArrayList<>(tracer.finishedSpans());
    result.removeAll(allSpans);
    allSpans.clear();
    allSpans.addAll(tracer.finishedSpans());
    return result;
  }
}
