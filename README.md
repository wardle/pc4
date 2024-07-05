
<h1>pc4</h1>

pc4 uses the polylith approach to structuring a codebase in which there are multiple components that are combined together via projects.


# Development

Run a nrepl at the top-level and this will include all components:

```shell
clj -M:dev:nrepl
```

Run eastwood linter:

```shell
clj -M:dev:lint/eastwood
```



