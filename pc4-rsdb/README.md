# pc4-rsdb

This provides support for legacy 'rsdb', which was first deployed in 2009. 
As might be expected, there are many problems with some early design decisions
but a big-bang rewrite is impractical so migration has to be incremental, with
newer functionality gradually replacing the old. 

Some planned work will entirely replace legacy structures; some design
of this module is geared towards making that replacement as straightforward
as possible.

Eventually, this module will take over managing the legacy database schema and 
migrations, but at the moment this module will not make *any* schema changes. 
Once this module is responsible for schema migrations, it will be 
straightforward to run unit tests against a on-demand created test database. 
Instead, to support the legacy development workflow, this module runs tests 
against an already established development database instance. This means this 
module cannot be easily tested unless you have access to the legacy application.

It is an explicit design goal of this module to use namespace/attribute names
that in most cases represent the underlying database schema. However, in places
a more normalized API may be provided in order to fix the most egregious 
problems in the semantics of reads or writes. It will usually be the 
responsibility of consumers of this library to normalize to a more modern
semantic representation.

##### Development

Run tests (you'll need a legacy development/test db instance configured):

```shell
clj -M:test
```

Create a library jar:
```shell
clj -T:build jar
```

Install library into local maven repository:
```shell
clj -T:build install
```