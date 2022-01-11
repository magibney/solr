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
package org.apache.solr.response.transform;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.google.common.base.Strings;
import org.apache.lucene.index.IndexableField;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.params.CommonParams;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.common.util.JavaBinCodec;
import org.apache.solr.common.util.JavaBinCodec.ObjectResolver;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.common.util.TextWriter;
import org.apache.solr.common.util.WriteableValue;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.response.QueryResponseWriter;

/**
 * @since solr 5.2
 */
public class RawValueTransformerFactory extends TransformerFactory
{
  String applyToWT = null;
  
  public RawValueTransformerFactory() {
    
  }

  public RawValueTransformerFactory(String wt) {
    this.applyToWT = wt;
  }
  
  @Override
  public void init(NamedList<?> args) {
    super.init(args);
    if(defaultUserArgs!=null&&defaultUserArgs.startsWith("wt=")) {
      applyToWT = defaultUserArgs.substring(3);
    }
  }
  
  @Override
  public DocTransformer create(String display, SolrParams params, SolrQueryRequest req) {
    String field = params.get("f");
    if(Strings.isNullOrEmpty(field)) {
      field = display;
    }
    // When a 'wt' is specified in the transformer, only apply it to the same wt
    boolean apply = true;
    if(applyToWT!=null) {
      String qwt = req.getParams().get(CommonParams.WT);
      if(qwt==null) {
        QueryResponseWriter qw = req.getCore().getQueryResponseWriter(req);
        QueryResponseWriter dw = req.getCore().getQueryResponseWriter(applyToWT);
        if(qw!=dw) {
          apply = false;
        }
      }
      else {
        apply = applyToWT.equals(qwt);
      }
    }

    if(apply) {
      return new RawTransformer( field, display, applyToWT );
    }
    
    if (field.equals(display)) {
      // we have to ensure the field is returned
      return new DocTransformer.NoopFieldTransformer(field);
    }
    return new RenameFieldTransformer( field, display, false );
  }

  public static Set<String> getRawFields(DocTransformer t, String wt) {
    if (t == null || wt == null) {
      return null;
    } else if (t instanceof RawTransformer) {
      RawTransformer rt = (RawTransformer) t;
      if (wt.equals(rt.applyToWT)) {
        return Collections.singleton(((RawTransformer)t).display);
      }
    } else if (t instanceof DocTransformers) {
      DocTransformers ts = (DocTransformers) t;
      List<String> fields = new ArrayList<>(ts.size());
      for (int i = ts.size() - 1; i >= 0; i--) {
        t = ts.getTransformer(i);
        RawTransformer rt;
        if (t instanceof RawTransformer && wt.equals((rt = (RawTransformer) t).applyToWT)){
          fields.add(rt.display);
        }
      }
      return fields.isEmpty() ? null : new HashSet<>(fields);
    }
    return null;
  }

  static class RawTransformer extends DocTransformer
  {
    final String field;
    final String display;
    final String applyToWT;

    public RawTransformer( String field, String display, String applyToWT )
    {
      this.field = field;
      this.display = display;
      this.applyToWT = applyToWT;
    }

    @Override
    public String getName()
    {
      return display;
    }

    @Override
    public void transform(SolrDocument doc, int docid) {
      Object val = doc.remove(field);
      if(val != null) {
        doc.setField(display, val);
      }
    }

    @Override
    public String[] getExtraRequestFields() {
      return new String[] {this.field};
    }
  }
  
  public static class WriteableStringValue extends WriteableValue {
    public final Object val;
    
    public WriteableStringValue(Object val) {
      this.val = val;
    }
    
    @Override
    public void write(String name, TextWriter writer) throws IOException {
      String str = null;
      if(val instanceof IndexableField) { // delays holding it in memory
        str = ((IndexableField)val).stringValue();
      }
      else {
        str = val.toString();
      }
      writer.getWriter().write(str);
    }

    @Override
    public Object resolve(Object o, JavaBinCodec codec) throws IOException {
      ObjectResolver orig = codec.getResolver();
      if(orig != null) {
        codec.writeVal(orig.resolve(val, codec));
        return null;
      }
      return val.toString();
    }
  }
}


