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
package org.apache.solr.search;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.ReaderUtil;
import org.apache.lucene.search.DocIdSet;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.util.Accountable;
import org.apache.lucene.util.BitSetIterator;
import org.apache.lucene.util.Bits;
import org.apache.lucene.util.FixedBitSet;
import org.apache.lucene.util.RamUsageEstimator;

/**
 * A {@link FixedBitSet} based implementation of a {@link DocSet}.  Good for medium/large sets.
 *
 * @since solr 0.9
 */
public class BitDocSet extends DocSet {
  private static final long BASE_RAM_BYTES_USED = RamUsageEstimator.shallowSizeOfInstance(BitDocSet.class)
      + RamUsageEstimator.shallowSizeOfInstance(FixedBitSet.class)
      + RamUsageEstimator.NUM_BYTES_ARRAY_HEADER;  // for the array object inside the FixedBitSet. long[] array won't change alignment, so no need to calculate it.

  // TODO consider SparseFixedBitSet alternative

  private final FixedBitSet bits;
  int size;    // number of docs in the set (cached for perf)

  public BitDocSet() {
    bits = new FixedBitSet(64);
  }

  /**
   * Construct a BitDocSet. The capacity of the {@link FixedBitSet} should be at
   * least maxDoc()
   */
  public BitDocSet(FixedBitSet bits) {
    this.bits = bits;
    size=-1;
  }

  /**
   * Construct a BitDocSet, and provides the number of set bits. The capacity of
   * the {@link FixedBitSet} should be at least maxDoc()
   */
  public BitDocSet(FixedBitSet bits, int size) {
    this.bits = bits;
    this.size = size;
  }

  @Override
  public DocIterator iterator() {
    return new DocIterator() {
      private final BitSetIterator iter = new BitSetIterator(bits, 0L); // cost is not useful here
      private int pos = iter.nextDoc();
      @Override
      public boolean hasNext() {
        return pos != DocIdSetIterator.NO_MORE_DOCS;
      }

      @Override
      public Integer next() {
        return nextDoc();
      }

      @Override
      public void remove() {
        bits.clear(pos);
      }

      @Override
      public int nextDoc() {
        int old=pos;
        pos=iter.nextDoc();
        return old;
      }

      @Override
      public float score() {
        return 0.0f;
      }
    };
  }

  /**
   * @return the <b>internal</b> {@link FixedBitSet} that should <b>not</b> be modified.
   */
  @Override
  public FixedBitSet getBits() {
    return bits;
  }

  @Override
  protected FixedBitSet getFixedBitSet() {
    return bits;
  }

  @Override
  protected FixedBitSet getFixedBitSetClone() {
    return bits.clone();
  }

  @Override
  public int size() {
    if (size!=-1) return size;
    return size = bits.cardinality();
  }

  /**
   * Returns true of the doc exists in the set. Should only be called when doc &lt;
   * {@link FixedBitSet#length()}.
   */
  @Override
  public boolean exists(int doc) {
    return bits.get(doc);
  }

  @Override
  public DocSet intersection(DocSet other) {
    // intersection is overloaded in the smaller DocSets to be more
    // efficient, so dispatch off of it instead.
    if (!(other instanceof BitDocSet)) {
      return other.intersection(this);
    }

    // Default... handle with bitsets.
    FixedBitSet newbits = getFixedBitSetClone();
    newbits.and(other.getFixedBitSet());
    return new BitDocSet(newbits);
  }

  @Override
  public int intersectionSize(DocSet other) {
    if (other instanceof BitDocSet) {
      return (int) FixedBitSet.intersectionCount(this.bits, ((BitDocSet) other).bits);
    } else {
      // they had better not call us back!
      return other.intersectionSize(this);
    }
  }

  @Override
  public boolean intersects(DocSet other) {
    if (other instanceof BitDocSet) {
      return bits.intersects(((BitDocSet)other).bits);
    } else {
      // they had better not call us back!
      return other.intersects(this);
    }
  }

  @Override
  public int unionSize(DocSet other) {
    if (other instanceof BitDocSet) {
      // if we don't know our current size, this is faster than
      // size + other.size - intersection_size
      return (int) FixedBitSet.unionCount(this.bits, ((BitDocSet) other).bits);
    } else {
      // they had better not call us back!
      return other.unionSize(this);
    }
  }

  @Override
  public int andNotSize(DocSet other) {
    if (other instanceof BitDocSet) {
      // if we don't know our current size, this is faster than
      // size - intersection_size
      return (int) FixedBitSet.andNotCount(this.bits, ((BitDocSet)other).bits);
    } else {
      return super.andNotSize(other);
    }
  }

  @Override
  public void addAllTo(FixedBitSet target) {
    target.or(bits);
  }

  @Override
  public DocSet andNot(DocSet other) {
    FixedBitSet newbits = bits.clone();
    if (other instanceof BitDocSet) {
      newbits.andNot(((BitDocSet) other).bits);
    } else {
      DocIterator iter = other.iterator();
      while (iter.hasNext()) {
        int doc = iter.nextDoc();
        if (doc < newbits.length()) {
          newbits.clear(doc);
        }
      }
    }
    return new BitDocSet(newbits);
  }
  
  @Override
  public DocSet union(DocSet other) {
    FixedBitSet newbits = bits.clone();
    if (other instanceof BitDocSet) {
      BitDocSet otherDocSet = (BitDocSet) other;
      newbits = FixedBitSet.ensureCapacity(newbits, otherDocSet.bits.length());
      newbits.or(otherDocSet.bits);
    } else {
      DocIterator iter = other.iterator();
      while (iter.hasNext()) {
        int doc = iter.nextDoc();
        newbits = FixedBitSet.ensureCapacity(newbits, doc);
        newbits.set(doc);
      }
    }
    return new BitDocSet(newbits);
  }
  
  @Override
  public BitDocSet clone() {
    return new BitDocSet(bits.clone(), size);
  }

  @Override
  public Bits getBits(LeafReaderContext context) {
    if (context.isTopLevel) {
      return bits;
    }

    final int base = context.docBase;
    final int length = context.reader().maxDoc();
    final FixedBitSet bs = bits;

    return new Bits() {
      @Override
      public boolean get(int index) {
        return bs.get(index + base);
      }

      @Override
      public int length() {
        return length;
      }
    };
  }

  private static final int NO_DOCS_THIS_SEGMENT = -1;
  private int[] cachedFloorDocs;

  /**
   * Because `bits.nextSetBit(int)` (called in `nextDoc()`) has no upper limit, lazily cache
   * floorDocs for each segment to avoid duplicate scanning of bits (and to enable optimization
   * in consumers afforded by returning <code>null</code> when there are no docs for a given
   * segment).
   */
  private int getFloorDoc(final LeafReaderContext ctx) {
    assert !ctx.isTopLevel;
    final int[] floorDocs;
    final int setMax = bits.length();
    if (cachedFloorDocs != null) {
      floorDocs = cachedFloorDocs;
    } else {
      List<LeafReaderContext> leaves = ReaderUtil.getTopLevelContext(ctx).leaves();
      floorDocs = new int[leaves.size()];
      int idx = 0;
      int nextFloorDoc = -1;
      for (LeafReaderContext c : leaves) {
        final int base = c.docBase;
        final int max = base + c.reader().maxDoc();
        final int recordFloorDoc;
        if (nextFloorDoc >= max) {
          recordFloorDoc = NO_DOCS_THIS_SEGMENT;
        } else if (nextFloorDoc >= base) {
          recordFloorDoc = nextFloorDoc;
        } else if (setMax <= base || (nextFloorDoc = bits.nextSetBit(base)) >= max) {
          recordFloorDoc = NO_DOCS_THIS_SEGMENT;
        } else {
          recordFloorDoc = nextFloorDoc;
        }
        floorDocs[idx++] = recordFloorDoc;
      }

      cachedFloorDocs = floorDocs;
    }
    return floorDocs[ctx.ord];
  }

  @Override
  public DocIdSetIterator iterator(LeafReaderContext context) {
    if (context.isTopLevel) {
      switch (size) {
        case 0:
          return null;
        default:
          // we have an explicit size; use it
          return new BitSetIterator(bits, size);
        case -1:
          // size has not been computed; use bits.length() as an upper bound on cost
          final int maxSize = bits.length();
          if (maxSize < 1) {
            return null;
          } else {
            return new BitSetIterator(bits, maxSize);
          }
      }
    }

    final int maxDoc = context.reader().maxDoc();
    if (maxDoc < 1) {
      // entirely empty segment; verified this actually happens
      return null;
    }

    final int firstDocId = getFloorDoc(context);
    if (firstDocId == NO_DOCS_THIS_SEGMENT) {
      return null;
    }
    final int base = context.docBase;
    final int max = base + maxDoc; // one past the max doc in this segment.
    final FixedBitSet bs = bits;

    return new DocIdSetIterator() {
      int pos = firstDocId - 1;
      int adjustedDoc = -1;

      @Override
      public int docID() {
        return adjustedDoc;
      }

      @Override
      public int nextDoc() {
        int next = pos + 1;
        if (next >= max) {
          return adjustedDoc = NO_MORE_DOCS;
        } else {
          pos = bs.nextSetBit(next);
          return adjustedDoc = pos < max ? pos - base : NO_MORE_DOCS;
        }
      }

      @Override
      public int advance(int target) {
        if (target == NO_MORE_DOCS) return adjustedDoc = NO_MORE_DOCS;
        int adjusted = target + base;
        if (adjusted >= max) {
          return adjustedDoc = NO_MORE_DOCS;
        } else {
          pos = bs.nextSetBit(adjusted);
          return adjustedDoc = pos < max ? pos - base : NO_MORE_DOCS;
        }
      }

      @Override
      public long cost() {
        // we don't want to actually compute cardinality, but
        // if it's already been computed, we use it (pro-rated for the segment)
        int maxDoc = max - base;
        if (size != -1) {
          return (long) (size * ((FixedBitSet.bits2words(maxDoc) << 6) / (float) bs.length()));
        } else {
          return maxDoc;
        }
      }
    };
  }

  @Override
  public Filter getTopFilter() {
    // TODO: if cardinality isn't cached, do a quick measure of sparseness
    // and return null from bits() if too sparse.

    return new Filter() {
      final FixedBitSet bs = bits;

      @Override
      public DocIdSet getDocIdSet(final LeafReaderContext context, final Bits acceptDocs) {
        // all Solr DocSets that are used as filters only include live docs
        final Bits acceptDocs2 = acceptDocs == null ? null : (context.reader().getLiveDocs() == acceptDocs ? null : acceptDocs);

        return BitsFilteredDocIdSet.wrap(new DocIdSet() {
          @Override
          public DocIdSetIterator iterator() {
            return BitDocSet.this.iterator(context);
          }

          @Override
          public long ramBytesUsed() {
            return BitDocSet.this.ramBytesUsed();
          }

          @Override
          public Bits bits() {
            return BitDocSet.this.getBits(context);
          }

        }, acceptDocs2);
      }

      @Override
      public String toString(String field) {
        return "BitSetDocTopFilter";
      }

      @Override
      public boolean equals(Object other) {
        return sameClassAs(other) &&
               Objects.equals(bs, getClass().cast(other).bs);
      }
      
      @Override
      public int hashCode() {
        return classHash() * 31 + bs.hashCode();
      }
    };
  }

  @Override
  public long ramBytesUsed() {
    return BASE_RAM_BYTES_USED + ((long)bits.getBits().length << 3);
  }

  @Override
  public Collection<Accountable> getChildResources() {
    return Collections.emptyList();
  }

  @Override
  public String toString() {
    return "BitDocSet{" +
        "size=" + size() +
        ",ramUsed=" + RamUsageEstimator.humanReadableUnits(ramBytesUsed()) +
        '}';
  }
}
