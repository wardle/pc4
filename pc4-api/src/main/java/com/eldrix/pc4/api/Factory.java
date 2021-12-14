package com.eldrix.pc4.api;

import com.eldrix.pc4.api.Hermes;

import java.util.List;
import java.util.function.Predicate;

import com.eldrix.hermes.snomed.Result;
import com.eldrix.hermes.snomed.ExtendedConcept;

public class Factory {

    private Hermes _hermes;

    private Factory() {
    }

    public static Factory factoryFromConfigurationFile(String filename) {
        return new Factory();
    }

    public Hermes getHermes() {
        return null;
    }

    public static void printResult(boolean result) {
        if (result) {
            System.out.println("✅");
        } else {
            System.out.println("❌");
        }
    }


    public static void main(String[] args) {
        System.out.println("pc4-api : running live test suite.");
        Hermes hermes = new Hermes("/Users/mark/Dev/hermes/snomed.db");
        // test search
        List<Result> results = hermes.search(SearchRequest.builder().setS("mnd").build());
        System.out.print("Hermes search and autocompletion: ");
        printResult(results.stream().anyMatch(r -> ((long) r.conceptId) == 37340000L));
        // test fetch
        com.eldrix.hermes.snomed.ExtendedConcept ec = hermes.fetchExtendedConcept(37340000L);
        System.out.print("Hermes fetch extended concept   : ");
        printResult(ec != null);
        System.out.print("Hermes subsumption 1/2          : ");
        printResult(hermes.subsumedBy(24700007, 6118003));  // MS is a type of demyelinating disease
        System.out.print("Hermes subsumption 2/2          : ");
        printResult(!(hermes.subsumedBy(24700007, 40733004))); // MS is not a type of infectious disease
    }
}
