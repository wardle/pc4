package com.eldrix.pc4.api;

import clojure.java.api.Clojure;

import java.util.HashMap;
import java.util.Map;

public class SearchRequest {

    Map<Object, Object> _params;

    private SearchRequest(Map<Object, Object> params) {
        _params = params;
    }

    public static class Builder {
        String _s;
        int _max_hits = -1;
        int _fuzzy = -1;
        int _fallback_fuzzy = -1;
        Boolean _show_fsn;
        String _constraint;
        Object _s_kw = Clojure.read(":s");
        Object _max_hits_kw = Clojure.read(":max-hits");
        Object _fuzzy_kw = Clojure.read(":fuzzy");
        Object _fallback_fuzzy_kw = Clojure.read(":fallback-fuzzy");
        Object _show_fsn_kw = Clojure.read(":show-fsn");
        Object _constraint_kw = Clojure.read(":constraint");

        public Builder setS(String s) {
            _s = s;
            return this;
        }
        public Builder setMaxHits(int max_hits) {
            _max_hits = max_hits;
            return this;
        }
        public Builder setFuzzy(int fuzzy) {
            _fuzzy = fuzzy;
            return this;
        }
        public Builder setFallbackFuzzy(int fallbackFuzzy) {
            _fallback_fuzzy = fallbackFuzzy;
            return this;
        }
        public Builder setShowFsn(boolean show) {
            _show_fsn = show;
            return this;
        }
        public Builder setConstraint(String ecl) {
            _constraint = ecl;
            return this;
        }
        public SearchRequest build() {
            HashMap<Object,Object> params = new HashMap<>();
            if (_s != null) {
                params.put(_s_kw, _s );
            }
            if (_max_hits >= 0) {
                params.put(_max_hits_kw, _max_hits);
            }
            if (_fuzzy >= 0) {
                params.put(_fuzzy_kw, _fuzzy);
            }
            if (_fallback_fuzzy >= 0) {
                params.put(_fallback_fuzzy_kw, _fallback_fuzzy);
            }
            if (_show_fsn != null) {
                params.put(_show_fsn_kw, _show_fsn);
            }
            if (_constraint != null) {
                params.put(_constraint_kw, _constraint);
            }
            return new SearchRequest(params);
        }
    }

    public static Builder builder() {
        return new Builder();
    }
}
