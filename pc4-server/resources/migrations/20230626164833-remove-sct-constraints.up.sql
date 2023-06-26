ALTER TABLE t_address DROP CONSTRAINT t_address_housing_concept_fk_concept_id_fk 
--;;
ALTER TABLE t_botulinum_toxin_injection DROP CONSTRAINT t_botulinum_toxin_injection_site_concept_fk_concept_id_fk 
--;;
ALTER TABLE t_cached_parent_concepts DROP CONSTRAINT t_cached_parent_concepts_child_concept_id_concept_id_fk 
--;;
ALTER TABLE t_cached_parent_concepts DROP CONSTRAINT t_cached_parent_concepts_parent_concept_id_concept_id_fk 
--;;
ALTER TABLE t_cross_map_table DROP CONSTRAINT t_cross_map_table_concept_id_concept_id_fk 
--;;
ALTER TABLE t_description DROP CONSTRAINT t_description_concept_id_concept_id_fk 
--;;
ALTER TABLE t_diagnosis DROP CONSTRAINT t_diagnosis_concept_fk_concept_id_fk 
--;;
ALTER TABLE t_document DROP CONSTRAINT t_document_concept_fk_concept_id_fk 
--;;
ALTER TABLE t_employment DROP CONSTRAINT t_employment_occupation_concept_fk_concept_id_fk 
--;;
ALTER TABLE t_encounter_template DROP CONSTRAINT t_encounter_template_concept_fk_concept_id_fk 
--;;
ALTER TABLE t_encounter_template DROP CONSTRAINT t_encounter_template_encounter_concept_fk_concept_id_fk 
--;;
ALTER TABLE t_epilepsy_term DROP CONSTRAINT t_epilepsy_term_concept_fk_concept_id_fk 
--;;
ALTER TABLE t_form_epilepsy_surgery_mdt DROP CONSTRAINT t_form_epilepsy_surgery_mdt_handedness_concept_fk_concept_id_fk 
--;;
ALTER TABLE t_form_item_result DROP CONSTRAINT t_form_item_result_concept_value_fk_concept_id_fk 
--;;
ALTER TABLE t_form_presenting_complaints_concepts DROP CONSTRAINT t_form_presenting_complaints_concepts_presenting_complaint_conc 
--;;
ALTER TABLE t_form_presenting_complaints DROP CONSTRAINT t_form_presenting_complaints_main_presenting_complaint_concept_ 
--;;
ALTER TABLE t_form_problems_concepts DROP CONSTRAINT t_form_problems_concepts_conceptconceptid_concept_id_fk 
--;;
ALTER TABLE t_form_procedure_botulinum_toxin DROP CONSTRAINT t_form_procedure_botulinum_toxin_indication_concept_fk_concept_ 
--;;
ALTER TABLE t_form_procedure_generic DROP CONSTRAINT t_form_procedure_generic_procedure_concept_fk_concept_id_fk 
--;;
ALTER TABLE t_result_eeg_background_findings DROP CONSTRAINT t_form_result_eeg_background_findings_concept_fk_concept_id_fk 
--;;
ALTER TABLE t_result_eeg_locations DROP CONSTRAINT t_form_result_eeg_locations_conceptconceptid_concept_id_fk 
--;;
ALTER TABLE t_result_eeg_ictal_findings DROP CONSTRAINT t_form_result_ictal_findings_concept_fk_concept_id_fk 
--;;
ALTER TABLE t_medication_event DROP CONSTRAINT t_medication_event_event_concept_fk_concept_id_fk 
--;;
ALTER TABLE t_medication DROP CONSTRAINT t_medication_medication_concept_fk_concept_id_fk 
--;;
ALTER TABLE t_metabolic_enzyme_application DROP CONSTRAINT t_metabolic_enzyme_application_drug_concept_fk_concept_id_fk 
--;;
ALTER TABLE t_metabolic_enzyme_eligibility DROP CONSTRAINT t_metabolic_enzyme_eligibility_diagnosis_concept_fk_concept_id_ 
--;;
ALTER TABLE t_metabolic_enzyme_eligibility DROP CONSTRAINT t_metabolic_enzyme_eligibility_drug_concept_fk_concept_id_fk 
--;;
ALTER TABLE t_ms_diagnosis DROP CONSTRAINT t_ms_diagnosis_concept_fk_concept_id_fk 
--;;
ALTER TABLE t_multiple_sclerosis_dmt DROP CONSTRAINT t_multiple_sclerosis_dmt_concept_fk_concept_id_fk 
--;;
ALTER TABLE t_patient DROP CONSTRAINT t_patient_country_of_birth_concept_fk_concept_id_fk 
--;;
ALTER TABLE t_patient DROP CONSTRAINT t_patient_ethnic_origin_concept_fk_concept_id_fk 
--;;
ALTER TABLE t_patient DROP CONSTRAINT t_patient_highest_educational_level_concept_fk_concept_id_fk 
--;;
ALTER TABLE t_patient DROP CONSTRAINT t_patient_occupation_concept_fk_concept_id_fk 
--;;
ALTER TABLE t_patient DROP CONSTRAINT t_patient_racial_group_concept_fk_concept_id_fk 
--;;
ALTER TABLE t_project_concept DROP CONSTRAINT t_project_concept_conceptconceptid_concept_id_fk 
--;;
ALTER TABLE t_project_recruitment_concept DROP CONSTRAINT t_project_recruitment_concept_conceptconceptid_concept_id_fk 
--;;
ALTER TABLE t_project DROP CONSTRAINT t_project_specialty_concept_fk_concept_id_fk 
--;;
ALTER TABLE t_relationship DROP CONSTRAINT t_relationship_relationship_type_concept_id_concept_id_fk 
--;;
ALTER TABLE t_relationship DROP CONSTRAINT t_relationship_source_concept_id_concept_id_fk 
--;;
ALTER TABLE t_relationship DROP CONSTRAINT t_relationship_target_concept_id_concept_id_fk 
--;;
