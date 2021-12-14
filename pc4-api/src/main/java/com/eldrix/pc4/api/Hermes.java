package com.eldrix.pc4.api;

import clojure.java.api.Clojure;
import clojure.lang.IFn;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import clojure.lang.Keyword;
import com.eldrix.hermes.snomed.Concept;
import com.eldrix.hermes.snomed.ExtendedConcept;
import com.eldrix.hermes.snomed.Description;
import com.eldrix.hermes.snomed.Result;

/**
 * Hermes provides a thin java API around the
 */
public class Hermes {
    private final Object _hermes_service;
    public static final IFn openFn;
    public static final IFn closeFn;
    public static final IFn searchFn;
    public static final IFn getConceptFn;
    public static final IFn getExtendedConceptFn;
    public static final IFn getPreferredSynonymFn;
    public static final IFn subsumedByFn;

    static {
        IFn require = Clojure.var("clojure.core", "require");
        require.invoke(Clojure.read("com.eldrix.hermes.core"));
        openFn = Clojure.var("com.eldrix.hermes.core", "open");
        closeFn = Clojure.var("com.eldrix.hermes.core", "close");

        searchFn = Clojure.var("com.eldrix.hermes.core", "search");
        getConceptFn = Clojure.var("com.eldrix.hermes.core", "get-concept");
        getExtendedConceptFn = Clojure.var("com.eldrix.hermes.core", "get-extended-concept");
        getPreferredSynonymFn = Clojure.var("com.eldrix.hermes.core", "get-preferred-synonym");
        subsumedByFn = Clojure.var("com.eldrix.hermes.core", "subsumed-by?");
    }

    public static Keyword keyword(String s) {
        return Keyword.intern(s);
    }

    /**
     * Open a hermes SNOMED terminology server at the path specified.
     *
     * @param path - path to database directory
     */
    public Hermes(String path) {
        _hermes_service = openFn.invoke(path);
    }

    public void close() {
        closeFn.invoke(_hermes_service);
    }

    public List<Result> search(SearchRequest request) {
        @SuppressWarnings("unchecked")
        List<Result> results = (List<Result>) searchFn.invoke(_hermes_service, request._params);
        return Collections.unmodifiableList(results);
    }

    public Concept fetchConcept(long conceptId) {
        return (Concept) getConceptFn.invoke(_hermes_service, conceptId);
    }

    public ExtendedConcept fetchExtendedConcept(long conceptId) {
        return (ExtendedConcept) getExtendedConceptFn.invoke(_hermes_service, conceptId);
    }

    public Description preferredSynonym(long conceptId, String languageTag) {
        return (Description) getPreferredSynonymFn.invoke(_hermes_service, conceptId, languageTag);
    }

    public Description preferredSynonym(long conceptId) {
        return preferredSynonym(conceptId, "en-GB");
    }

    public boolean isAConcept(Concept concept, Concept parent) {
        return (boolean) subsumedByFn.invoke(_hermes_service, concept.id, parent.id);
    }

    public boolean subsumedBy(long conceptId, long subsumerConceptId) {
        return (boolean) subsumedByFn.invoke(_hermes_service, conceptId, subsumerConceptId);
    }

    public static class SearchRequest {

        final Map<Object, Object> _params;

        private SearchRequest(Map<Object, Object> params) {
            _params = Collections.unmodifiableMap(params);
        }
    }

    public static class SearchRequestBuilder {
        String _s;
        int _max_hits = -1;
        int _fuzzy = -1;
        int _fallback_fuzzy = -1;
        Boolean _show_fsn;
        String _constraint;
        Object _s_kw = keyword("s");
        Object _max_hits_kw = keyword("max-hits");
        Object _fuzzy_kw = keyword("fuzzy");
        Object _fallback_fuzzy_kw = keyword("fallback-fuzzy");
        Object _show_fsn_kw = keyword(":show-fsn");
        Object _constraint_kw = keyword(":constraint");

        public SearchRequestBuilder setS(String s) {
            _s = s;
            return this;
        }
        public SearchRequestBuilder setMaxHits(int max_hits) {
            _max_hits = max_hits;
            return this;
        }
        public SearchRequestBuilder setFuzzy(int fuzzy) {
            _fuzzy = fuzzy;
            return this;
        }
        public SearchRequestBuilder setFallbackFuzzy(int fallbackFuzzy) {
            _fallback_fuzzy = fallbackFuzzy;
            return this;
        }
        public SearchRequestBuilder setShowFsn(boolean show) {
            _show_fsn = show;
            return this;
        }
        public SearchRequestBuilder setConstraint(String ecl) {
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

    public static SearchRequestBuilder searchRequestBuilder() {
        return new SearchRequestBuilder();
    }

}
