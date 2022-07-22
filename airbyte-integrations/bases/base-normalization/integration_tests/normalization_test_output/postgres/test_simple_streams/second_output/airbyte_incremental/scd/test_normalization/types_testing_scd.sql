
      
    delete from "postgres".test_normalization."types_testing_scd"
    where (_airbyte_unique_key_scd) in (
        select (_airbyte_unique_key_scd)
        from "types_testing_scd__dbt_tmp"
    );
    

    insert into "postgres".test_normalization."types_testing_scd" ("_airbyte_unique_key", "_airbyte_unique_key_scd", "id", "airbyte_integer", "nullable_airbyte_integer", "big_integer", "nullable_big_integer", "_airbyte_start_at", "_airbyte_end_at", "_airbyte_active_row", "_airbyte_ab_id", "_airbyte_emitted_at", "_airbyte_normalized_at", "_airbyte_types_testing_hashid")
    (
        select "_airbyte_unique_key", "_airbyte_unique_key_scd", "id", "airbyte_integer", "nullable_airbyte_integer", "big_integer", "nullable_big_integer", "_airbyte_start_at", "_airbyte_end_at", "_airbyte_active_row", "_airbyte_ab_id", "_airbyte_emitted_at", "_airbyte_normalized_at", "_airbyte_types_testing_hashid"
        from "types_testing_scd__dbt_tmp"
    )
  