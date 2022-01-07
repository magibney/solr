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
package org.apache.solr.search.facet;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.IntFunction;

import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.Query;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.util.SimpleOrderedMap;
import org.apache.solr.handler.component.ResponseBuilder;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.request.SolrRequestInfo;
import org.apache.solr.schema.SchemaField;
import org.apache.solr.search.BitDocSet;
import org.apache.solr.search.DocIterator;
import org.apache.solr.search.DocSet;
import org.apache.solr.search.QParser;
import org.apache.solr.search.SolrIndexSearcher;
import org.apache.solr.search.SyntaxError;
import org.apache.solr.search.facet.SlotAcc.SlotContext;

/** Base abstraction for a class that computes facets. This is fairly internal to the module. */
public abstract class FacetProcessor<T extends FacetRequest>  {
  SimpleOrderedMap<Object> response;
  FacetContext fcontext;
  // TODO : I'm not sure this needs to be generic but come back to this later
  T freq;

  DocSet filter;  // additional filters specified by "filter"  // TODO: do these need to be on the context to support recomputing during multi-select?
  LinkedHashMap<String,SlotAcc> accMap;
  SlotAcc[] accs;
  SlotAcc.CountSlotAcc countAcc;

  FacetProcessor(FacetContext fcontext, T freq) {
    this.fcontext = fcontext;
    this.freq = freq;
    fcontext.processor = this;
  }

  public org.apache.solr.common.MapWriter getResponse() {
    return response;
  }

  public void process() throws IOException {
    handleDomainChanges();
  }

  private void evalFilters() throws IOException {
    if (freq.domain.filters == null || freq.domain.filters.isEmpty()) return;
    this.filter = fcontext.searcher.getDocSet(evalJSONFilterQueryStruct(fcontext, freq.domain.filters));
  }
  
  private static List<Query> evalJSONFilterQueryStruct(FacetContext fcontext, List<Object> filters) throws IOException {
    List<Query> qlist = new ArrayList<>(filters.size());
    // TODO: prevent parsing filters each time!
    for (Object rawFilter : filters) {
      if (rawFilter instanceof String) {
        qlist.add(parserFilter((String) rawFilter, fcontext.req));
      } else if (rawFilter instanceof Map) {

        @SuppressWarnings({"unchecked"})
        Map<String,Object> m = (Map<String, Object>) rawFilter;
        String type;
        Object args;

        if (m.size() == 1) {
          Map.Entry<String, Object> entry = m.entrySet().iterator().next();
          type = entry.getKey();
          args = entry.getValue();
        } else {
          throw new SolrException(SolrException.ErrorCode.BAD_REQUEST, "Can't convert map to query:" + rawFilter);
        }

        if (!"param".equals(type)) {
          throw new SolrException(SolrException.ErrorCode.BAD_REQUEST, "Unknown type. Can't convert map to query:" + rawFilter);
        }

        String tag;
        if (!(args instanceof String)) {
          throw new SolrException(SolrException.ErrorCode.BAD_REQUEST, "Can't retrieve non-string param:" + args);
        }
        tag = (String)args;

        String[] qstrings = fcontext.req.getParams().getParams(tag);

        // idea is to support multivalued parameter ie, 0 or more values
        // so, when value not specified, it is ignored rather than throwing exception
        if (qstrings != null) {
          for (String qstring : qstrings) {
            qlist.add(parserFilter(qstring, fcontext.req));
          }
        }

      } else {
        throw new SolrException(SolrException.ErrorCode.BAD_REQUEST, "Bad query (expected a string):" + rawFilter);
      }

    }
    return qlist;
  }

  private static Query parserFilter(String rawFilter, SolrQueryRequest req) {
    QParser parser = null;
    try {
      parser = QParser.getParser(rawFilter, req);
      parser.setIsFilter(true);
      Query symbolicFilter = parser.getQuery();
      if (symbolicFilter == null) {
        throw new SolrException(SolrException.ErrorCode.BAD_REQUEST,
            "QParser yields null, perhaps unresolved parameter reference in: "+rawFilter);
      }
      return symbolicFilter;
    } catch (SyntaxError syntaxError) {
      throw new SolrException(SolrException.ErrorCode.BAD_REQUEST, syntaxError);
    }
  }

  private void handleDomainChanges() throws IOException {
    if (freq.domain == null) return;

    if (null != freq.domain.explicitQueries) {
      try {
        final List<Query> domainQs = evalJSONFilterQueryStruct(fcontext, freq.domain.explicitQueries);
        if (domainQs.isEmpty()) {
          throw new SolrException(SolrException.ErrorCode.BAD_REQUEST,
                                  "'query' domain must not evaluate to an empty list of queries");
        }
        fcontext.base = fcontext.searcher.getDocSet(domainQs);
      } catch (SolrException e) {
        throw new SolrException(SolrException.ErrorCode.BAD_REQUEST,
                                "Unable to parse domain 'query': " + freq.domain.explicitQueries +
                                " -- reason: " + e.getMessage(),
                                e);
      }
    } else {
      // mutualy exclusive to freq.domain.explicitQueries
      handleFilterExclusions();
    }

    // Check filters... if we do have filters they apply after domain changes.
    // We still calculate them first because we can use it in a parent->child domain change.
    evalFilters();

    handleJoinField();
    handleGraphField();

    boolean appliedFilters = handleBlockJoin();

    if (this.filter != null && !appliedFilters) {
      fcontext.base = fcontext.base.intersection( filter );
    }
  }

  private void handleFilterExclusions() throws IOException {
    List<String> excludeTags = freq.domain.excludeTags;

    if (excludeTags == null || excludeTags.size() == 0) {
      return;
    }

    Map<?,?> tagMap = (Map<?,?>) fcontext.req.getContext().get("tags");
    if (tagMap == null) {
      // no filters were tagged
      return;
    }

    IdentityHashMap<Query,Boolean> excludeSet = new IdentityHashMap<>();
    for (String excludeTag : excludeTags) {
      Object olst = tagMap.get(excludeTag);
      // tagMap has entries of List<String,List<QParser>>, but subject to change in the future
      if (!(olst instanceof Collection)) continue;
      for (Object o : (Collection<?>)olst) {
        if (!(o instanceof QParser)) continue;
        QParser qp = (QParser)o;
        try {
          excludeSet.put(qp.getQuery(), Boolean.TRUE);
        } catch (SyntaxError syntaxError) {
          // This should not happen since we should only be retrieving a previously parsed query
          throw new SolrException(SolrException.ErrorCode.BAD_REQUEST, syntaxError);
        }
      }
    }
    if (excludeSet.size() == 0) return;

    List<Query> qlist = new ArrayList<>();

    // TODO: somehow remove responsebuilder dependency
    ResponseBuilder rb = SolrRequestInfo.getRequestInfo().getResponseBuilder();

    // add the base query
    if (!excludeSet.containsKey(rb.getQuery())) {
      qlist.add(rb.getQuery());
    }

    // add the filters
    if (rb.getFilters() != null) {
      for (Query q : rb.getFilters()) {
        if (!excludeSet.containsKey(q)) {
          qlist.add(q);
        }
      }
    }

    // now walk back up the context tree
    // TODO: we lose parent exclusions...
    for (FacetContext curr = fcontext; curr != null; curr = curr.parent) {
      if (curr.filter != null) {
        qlist.add( curr.filter );
      }
    }

    // recompute the base domain
    fcontext.base = fcontext.searcher.getDocSet(qlist);
  }

  /** modifies the context base if there is a join field domain change */
  private void handleJoinField() throws IOException {
    if (null == freq.domain.joinField) return;

    final Query domainQuery = freq.domain.joinField.createDomainQuery(fcontext);
    fcontext.base = fcontext.searcher.getDocSet(domainQuery);
  }

  /** modifies the context base if there is a graph field domain change */
  private void handleGraphField() throws IOException {
    if (null == freq.domain.graphField) return;

    final Query domainQuery = freq.domain.graphField.createDomainQuery(fcontext);
    fcontext.base = fcontext.searcher.getDocSet(domainQuery);
  }

  // returns "true" if filters were applied to fcontext.base already
  private boolean handleBlockJoin() throws IOException {
    boolean appliedFilters = false;
    if (!(freq.domain.toChildren || freq.domain.toParent)) return appliedFilters;

    // TODO: avoid query parsing per-bucket somehow...
    String parentStr = freq.domain.parents;
    Query parentQuery;
    try {
      QParser parser = QParser.getParser(parentStr, fcontext.req);
      parser.setIsFilter(true);
      parentQuery = parser.getQuery();
    } catch (SyntaxError err) {
      throw new SolrException(SolrException.ErrorCode.BAD_REQUEST, "Error parsing block join parent specification: " + parentStr);
    }

    BitDocSet parents = fcontext.searcher.getDocSetBits(parentQuery);
    DocSet input = fcontext.base;
    DocSet result;

    if (freq.domain.toChildren) {
      // If there are filters on this facet, then use them as acceptDocs when executing toChildren.
      // We need to remember to not redundantly re-apply these filters after.
      DocSet acceptDocs = this.filter;
      if (acceptDocs == null) {
        acceptDocs = fcontext.searcher.getLiveDocSet();
      } else {
        appliedFilters = true;
      }
      result = BlockJoin.toChildren(input, parents, acceptDocs, fcontext.qcontext);
    } else {
      result = BlockJoin.toParents(input, parents, fcontext.qcontext);
    }

    fcontext.base = result;
    return appliedFilters;
  }

  protected void processStats(SimpleOrderedMap<Object> bucket, Query bucketQ, DocSet docs, long docCount) throws IOException {
    if (docCount == 0 && !freq.processEmpty || freq.getFacetStats().size() == 0) {
      bucket.add("count", docCount);
      return;
    }
    createAccs(docCount, 1);
    long collected = collect(docs, 0, slotNum -> { return new SlotContext(bucketQ); });
    countAcc.incrementCount(0, collected);
    assert collected == docCount;
    addStats(bucket, 0);
  }

  protected void createAccs(long docCount, int slotCount) throws IOException {
    accMap = new LinkedHashMap<>();

    // allow a custom count acc to be used
    if (countAcc == null) {
      countAcc = new SlotAcc.CountSlotArrAcc(fcontext, slotCount);
    }

    for (Map.Entry<String,AggValueSource> entry : freq.getFacetStats().entrySet()) {
      SlotAcc acc = entry.getValue().createSlotAcc(fcontext, docCount, slotCount);
      acc.key = entry.getKey();
      accMap.put(acc.key, acc);
    }

    accs = new SlotAcc[accMap.size()];
    int i=0;
    for (SlotAcc acc : accMap.values()) {
      accs[i++] = acc;
    }
  }

  // note: only called by enum/stream prior to collect
  void resetStats() throws IOException {
    countAcc.reset();
    for (SlotAcc acc : accs) {
      acc.reset();
    }
  }

  long collect(DocSet docs, int slot, IntFunction<SlotContext> slotContext) throws IOException {
    long count = 0;
    SolrIndexSearcher searcher = fcontext.searcher;

    if (0 == docs.size()) {
      // we may be in a "processEmpty" type situation where the client still cares about this bucket
      // either way, we should let our accumulators know about the empty set, so they can collect &
      // compute the slot (ie: let them decide if they care even when it's size==0)
      if (accs != null) {
        for (SlotAcc acc : accs) {
          acc.collect(docs, slot, slotContext); // NOT per-seg collectors
        }
      }
      return count;
    }
    
    final List<LeafReaderContext> leaves = searcher.getIndexReader().leaves();
    final Iterator<LeafReaderContext> ctxIt = leaves.iterator();
    LeafReaderContext ctx = null;
    int segBase = 0;
    int segMax;
    int adjustedMax = 0;
    for (DocIterator docsIt = docs.iterator(); docsIt.hasNext(); ) {
      final int doc = docsIt.nextDoc();
      if (doc >= adjustedMax) {
        do {
          ctx = ctxIt.next();
          if (ctx == null) {
            // should be impossible
            throw new RuntimeException("INTERNAL FACET ERROR");
          }
          segBase = ctx.docBase;
          segMax = ctx.reader().maxDoc();
          adjustedMax = segBase + segMax;
        } while (doc >= adjustedMax);
        assert doc >= ctx.docBase;
        setNextReader(ctx);
      }
      count++;
      collect(doc - segBase, slot, slotContext);  // per-seg collectors
    }
    return count;
  }

  void collect(int segDoc, int slot, IntFunction<SlotContext> slotContext) throws IOException {
    if (accs != null) {
      for (SlotAcc acc : accs) {
        acc.collect(segDoc, slot, slotContext);
      }
    }
  }

  void setNextReader(LeafReaderContext ctx) throws IOException {
    // countAcc.setNextReader is a no-op
    for (SlotAcc acc : accs) {
      acc.setNextReader(ctx);
    }
  }

  void addStats(SimpleOrderedMap<Object> target, int slotNum) throws IOException {
    long count = countAcc.getCount(slotNum);
    target.add("count", count);
    if (count > 0 || freq.processEmpty) {
      for (SlotAcc acc : accs) {
        acc.setValues(target, slotNum);
      }
    }
  }

  void fillBucket(SimpleOrderedMap<Object> bucket, Query q, DocSet result, boolean skip, Map<String,Object> facetInfo) throws IOException {

    boolean needDocSet = (skip==false && freq.getFacetStats().size() > 0) || freq.getSubFacets().size() > 0;

    long count;

    if (result != null) {
      count = result.size();
    } else if (needDocSet) {
      if (q == null) {
        result = fcontext.base;
        // result.incref(); // OFF-HEAP
      } else {
        result = fcontext.searcher.getDocSet(q, fcontext.base);
      }
      count = result.size();  // don't really need this if we are skipping, but it's free.
    } else {
      if (q == null) {
        count = fcontext.base.size();
      } else {
        count = fcontext.searcher.numDocs(q, fcontext.base);
      }
    }

    try {
      if (!skip) {
        processStats(bucket, q, result, count);
      }
      processSubs(bucket, q, result, skip, facetInfo);
    } finally {
      if (result != null) {
        // result.decref(); // OFF-HEAP
        result = null;
      }
    }
  }

  static final class AugmentEntries {
    private final int leafEntries;
    private final int partialEntries;
    private final Map<Object, Object> entries;
    private final Map<String, Object> origFacetInfo;
    private Function<Object, Object> toNativeType;

    private AugmentEntries(int leafEntries, int partialEntries, Map<Object, Object> entries, Map<String, Object> origFacetInfo) {
      this.leafEntries = leafEntries;
      this.partialEntries = partialEntries;
      this.entries = entries;
      this.origFacetInfo = origFacetInfo;
    }

    void transformKeysToNativeType(Function<Object, Object> toNativeType) {
      this.toNativeType = toNativeType;
      for (final Object key : entries.keySet().toArray()) {
        final Object transformed = toNativeType.apply(key);
        if (key.getClass() != transformed.getClass()) {
          entries.put(transformed, entries.remove(key));
        }
      }
    }

    private Map<String, Object> getBulkPhasePartialFacetInfo() {
      if (partialEntries == 0) {
        return null;
      }
      // we only care about "augmented partial" facets here, because we need to make sure that enumerated vals for
      // partial subs are propagated down, even if a val for a given sub is evaluted during the "bulk collection"
      // phase of augmentation.
      return Collections.singletonMap("_q", origFacetInfo.get("_q"));
    }

    private Map<String, Object> getFacetInfo() {
      if (entries.size() == leafEntries + partialEntries) {
        // nothing was removed; simply rename the keys
        if (leafEntries != 0) {
          origFacetInfo.put("_l", origFacetInfo.remove("_a"));
        }
        if (partialEntries != 0) {
          origFacetInfo.put("_p", origFacetInfo.remove("_q"));
        }
        return origFacetInfo;
      } else if (partialEntries == 0) {
        // common case
        return Collections.singletonMap("_l", Arrays.asList(entries.keySet().toArray()));
      } else if (leafEntries == 0) {
        // _far_ less common, but easy
        final List<List<?>> partial = new ArrayList<>(entries.size());
        for (Map.Entry<Object, Object> e : entries.entrySet()) {
          partial.add(List.of(e.getKey(), e.getValue()));
        }
        return Collections.singletonMap("_p", partial);
      } else {
        final int maxSize = entries.size();
        final List<Object> leaves = new ArrayList<>(Math.min(leafEntries, maxSize));
        final List<List<?>> partial = new ArrayList<>(Math.min(partialEntries, maxSize));
        for (Map.Entry<Object, Object> e : entries.entrySet()) {
          final Object val = e.getValue();
          if (val == null) {
            leaves.add(e.getKey());
          } else {
            partial.add(List.of(e.getKey(), val));
          }
        }
        Map<String, Object> ret = new HashMap<>(2);
        ret.put("_l", leaves);
        ret.put("_p", partial);
        return ret;
      }
    }
  }

  @SuppressWarnings({"unchecked"})
  private AugmentEntries getAugmentEntries(Map<String,Object> facetInfoSub) {
    Object tmp;
    final List<Object> augmentLeaf = (tmp = facetInfoSub.get("_a")) == null ? null : (List<Object>) tmp;
    final List<List<Object>> augmentPartial;
    if ((tmp = facetInfoSub.get("_q")) == null) {
      if (augmentLeaf == null) {
        return null;
      }
      augmentPartial = null;
    } else {
      augmentPartial = (List<List<Object>>) tmp;
    }
    Map<Object, Object> ret = null;
    int leafSize = 0;
    int partialSize = 0;
    if (augmentLeaf != null) {
      ret = new HashMap<>((leafSize = augmentLeaf.size()) + (augmentPartial == null ? 0 : (partialSize = augmentPartial.size())));
      for (Object o : augmentLeaf) {
        ret.put(o, null);
      }
    }
    if (augmentPartial != null) {
      if (ret == null) {
        ret = new HashMap<>(partialSize = augmentPartial.size());
      }
      for (List<Object> o : augmentPartial) {
        ret.put(o.get(0), o.get(1));
      }
    }
    return new AugmentEntries(leafSize, partialSize, ret, facetInfoSub);
  }

  protected static boolean isRefining(Map<String,Object> facetInfo) {
    return facetInfo != null && (facetInfo.size() != 1 || !facetInfo.containsKey("_q"));
  }

  protected Function<Object, Object> toNativeType() {
    return (o) -> o;
  }

  @SuppressWarnings({"unchecked"})
  void processSubs(SimpleOrderedMap<Object> response, Query filter, DocSet domain, boolean skip, Map<String,Object> facetInfo) throws IOException {

    boolean emptyDomain = domain == null || domain.size() == 0;

    for (Map.Entry<String,FacetRequest> sub : freq.getSubFacets().entrySet()) {
      FacetRequest subRequest = sub.getValue();

      Map<String,Object> facetInfoSub = null;
      if (facetInfo != null) {
        facetInfoSub = (Map<String,Object>)facetInfo.get(sub.getKey());
      }

      // This includes a static check if a sub-facet can possibly produce something from
      // an empty domain.  Should this be changed to a dynamic check as well?  That would
      // probably require actually executing the facet anyway, and dropping it at the
      // end if it was unproductive.
      if (emptyDomain && !freq.processEmpty && !subRequest.canProduceFromEmpty(facetInfoSub != null)) {
        continue;
      }

      final AugmentEntries augment;
      if (facetInfoSub != null) {
        if ((augment = getAugmentEntries(facetInfoSub)) != null) {
          // augment
          assert facetInfoSub.get("_s") == null && facetInfoSub.get("_p") == null && facetInfoSub.get("_l") == null;
          facetInfoSub = augment.getBulkPhasePartialFacetInfo();
        }
      } else if (skip) {
        // If we're skipping this node, then we only need to process sub-facets that have facet info specified.
        continue;
      } else if (subRequest.evaluateAsTopLevel() && fcontext.isShard()) {
        // defer evaluateAtTopLevel requests until requested via augment ("_a") `facetInfoSub` under "skip" bucket
        continue;
      } else {
        augment = null;
      }

      // make a new context for each sub-facet since they can change the domain
      FacetContext subContext = fcontext.sub(filter, domain);
      subContext.facetInfo = facetInfoSub;
      subContext.augment = augment;
      if (!skip) subContext.flags &= ~FacetContext.SKIP_FACET;  // turn off the skip flag if we're not skipping this bucket

      if (fcontext.getDebugInfo() != null) {   // if fcontext.debugInfo != null, it means rb.debug() == true
        FacetDebugInfo fdebug = new FacetDebugInfo();
        subContext.setDebugInfo(fdebug);
        fcontext.getDebugInfo().addChild(fdebug);
      }

      Object result = subRequest.process(subContext);

      if (augment != null) {
        // make sure that all buckets specifically enumerated are present in the response
        // TODO: It would be more efficient if we could inline this with bulk collection in a single pass.
        //  This should indeed be possible, at least for the "String field" case. The tricky (?) part would
        //  be converting the specified values to something that can be efficiently checked for equality against
        //  values during bulk collection.
        final List<SimpleOrderedMap<?>> buckets = (List<SimpleOrderedMap<?>>) ((SimpleOrderedMap<Object>) result).get("buckets");

        // TODO: circle back and figure out what's going on here. We should _not_ have to apply this `toNativeType`
        //  on the vals returned from the response -- e.g.: why are we getting `Long` vals returned for `StrField` type?
        //  Reproduce:
        //  gradlew --console=plain :solr:core:test --tests "org.apache.solr.search.facet.TestCloudJSONFacetSKG.testRandom"
        //            -Ptests.jvms=4 -Ptests.jvmargs=-XX:TieredStopAtLevel=1 -Ptests.seed=8B4038889D0A1CB1
        //            -Ptests.file.encoding=US-ASCII
        Object val = null;
        final Function<Object, Object> toNativeType = augment.toNativeType;
        for (SimpleOrderedMap<?> bucket : buckets) {
          // prune any enumerated values that are already represented as a result of bulk collection
          augment.entries.remove(val = toNativeType.apply(bucket.get("val")));
        }
        // this is just a sanity check to detect blatant incompatibility between types as specified for refinement
        // request "augmentation", and types as returned from the initial "bulk collection" request. (we only check
        // one value here -- the last one)
        assert validateCompatibleTypes(val, augment);
        if (!augment.entries.isEmpty()) {
          // specifically collect any values not already represented.
          subContext.facetInfo = augment.getFacetInfo();
          Object augmentResult = subRequest.process(subContext);
          buckets.addAll((List<SimpleOrderedMap<?>>)((SimpleOrderedMap<?>)augmentResult).get("buckets"));
        }
      }

      response.add( sub.getKey(), result);
    }
  }

  private static boolean validateCompatibleTypes(Object fromResponse, AugmentEntries augment) {
    if (fromResponse != null && !augment.entries.isEmpty()) {
      final Class<?> fromResponseClass = fromResponse.getClass();
      final Class<?> fromRequestClass = augment.entries.keySet().iterator().next().getClass();
      assert fromResponseClass == fromRequestClass : "request "+fromRequestClass+" incompatible with response "+fromResponseClass;
    }
    return true;
  }
  @SuppressWarnings("unused")
  static DocSet getFieldMissing(SolrIndexSearcher searcher, DocSet docs, String fieldName) throws IOException {
    SchemaField sf = searcher.getSchema().getField(fieldName);
    DocSet hasVal = searcher.getDocSet(sf.getType().getRangeQuery(null, sf, null, null, false, false));
    DocSet answer = docs.andNot(hasVal);
    // hasVal.decref(); // OFF-HEAP
    return answer;
  }

  static Query getFieldMissingQuery(SolrIndexSearcher searcher, String fieldName) throws IOException {
    SchemaField sf = searcher.getSchema().getField(fieldName);
    Query hasVal = sf.getType().getRangeQuery(null, sf, null, null, false, false);
    BooleanQuery.Builder noVal = new BooleanQuery.Builder();
    noVal.add(hasVal, BooleanClause.Occur.MUST_NOT);
    return noVal.build();
  }

}
