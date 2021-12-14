package com.eldrix.pc4.api;

import clojure.java.api.Clojure;
import clojure.lang.IFn;
import com.eldrix.clods.core.ODS;

public class Ods {
    private final ODS _odsService;

    public final static IFn openFn;
    public final static IFn closeFn;
    public final static IFn fetchPostcodeFn;

    static {
        IFn require = Clojure.var("clojure.core", "require");
        require.invoke(Clojure.read("com.eldrix.clods.core"));
        openFn = Clojure.var("com.eldrix.clods.core", "open-index");
        closeFn = Clojure.var("com.eldrix.clods.core", "close");
        fetchPostcodeFn = Clojure.var("com.eldrix.clods.core", "fetch-postcode");
    }

    public Ods(String odsDir, String nhspdDir) {
        _odsService = (ODS) openFn.invoke(odsDir, nhspdDir);
    }
    public void close() {
        closeFn.invoke(_odsService);
    }

    public Object fetchPostcode(String postcode) {
        return fetchPostcodeFn.invoke(_odsService, postcode);
    }

}
