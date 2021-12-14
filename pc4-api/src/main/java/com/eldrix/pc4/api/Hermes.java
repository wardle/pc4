package com.eldrix.pc4.api;

import clojure.java.api.Clojure;
import clojure.lang.IFn;

import java.util.Collections;
import java.util.List;

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

}
