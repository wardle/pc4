(ns com.eldrix.pc4.server.rsdb.updater
  "A collection of tools to support updates to the legacy rsdb backend.

  rsdb v3 and earlier were monolithic applications with a single PostgreSQL
  backend.

  We may migrate away from PostgreSQL altogether, or at least, migrate to a
  different database schema, built upon immutable data. With unlimited time
  and resources, I'd build from the ground-up, and simply use legacy rsdb to
  pre-populate in read-only mode, with all users moving to v4 at the same time.

  However, we are going to have to run in parallel for some time with some users
  using v4, and others using v3, as functionality is ported across.

  That means we need to be able to upgrade legacy rsdb so we can continue to
  use as a backend for both v3 and v4 concurrently.

  This does not prevent future migration to a new data backend, perhaps
  datomic. But that cannot be at the same time as migrating to the v4
  front-end(s).

  The following reference data are stored:
  * SNOMED CT RF1 - in tables
        - t_concept
        - t_description
        - t_relationship
        - t_cached_parent_concepts
  * UK organisation data
        - t_health_authority
        - t_trust
        - t_hospital
        - t_surgery
        - t_general_practitioner
        - t_postcode

  This namespace provides functions that can take modern reference data sources
  and update the v3 backend so it may continue to run safely.")



