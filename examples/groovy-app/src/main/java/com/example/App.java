package com.example;

/** Prints the environment gradle-envoy injected into this (Gradle-forked) JVM. */
public class App {
    public static void main(String[] args) {
        System.out.println("ENVOY_EXAMPLE_GREETING = " + System.getenv("ENVOY_EXAMPLE_GREETING"));
        System.out.println("ENVOY_EXAMPLE_TOKEN    = " + System.getenv("ENVOY_EXAMPLE_TOKEN"));
    }
}
