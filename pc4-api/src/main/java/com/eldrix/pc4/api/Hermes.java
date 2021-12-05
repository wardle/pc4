package com.eldrix.pc4.api;

import clojure.java.api.Clojure;
import clojure.lang.IFn;

import java.util.Collections;
import java.util.List;

import com.eldrix.hermes.snomed.ExtendedConcept;
import com.eldrix.hermes.snomed.Result;

public class Hermes {
    private final Object _hermes_service;

    static {
        IFn require = Clojure.var("clojure.core", "require");
        require.invoke(Clojure.read("com.eldrix.hermes.core"));
    }

    /**
     * Open a hermes SNOMED terminology server at the path specified.
     *
     * @param path - path to database directory
     */
    public Hermes(String path) {
        IFn open_hermes = Clojure.var("com.eldrix.hermes.core", "open");
        _hermes_service = open_hermes.invoke(path);
    }

    public List<Result> search(SearchRequest request) {
        IFn search = Clojure.var("com.eldrix.hermes.core", "search");
        @SuppressWarnings("unchecked")
        List<Result> results = (List<Result>)search.invoke(_hermes_service, request._params);
        return Collections.unmodifiableList(results);
    }

    public ExtendedConcept fetchExtendedConcept(long conceptId) {
        IFn get_extended_concept = Clojure.var("com.eldrix.hermes.core", "get-extended-concept");
        return (ExtendedConcept) get_extended_concept.invoke(_hermes_service, conceptId);
    }

    public void close() {
        IFn close_hermes = Clojure.var("com.eldrix.hermes.core", "close");
        close_hermes.invoke(_hermes_service);
    }
}
