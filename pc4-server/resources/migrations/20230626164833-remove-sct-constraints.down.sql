ALTER TABLE t_address ADD CONSTRAINT t_address_housing_concept_fk_concept_id_fk FOREIGN KEY (housing_concept_fk) REFERENCES t_concept(concept_id) DEFERRABLE INITIALLY DEFERRED
--;;
ALTER TABLE t_botulinum_toxin_injection ADD CONSTRAINT t_botulinum_toxin_injection_site_concept_fk_concept_id_fk FOREIGN KEY (site_concept_fk) REFERENCES t_concept(concept_id) DEFERRABLE INITIALLY DEFERRED
--;;
ALTER TABLE t_cached_parent_concepts ADD CONSTRAINT t_cached_parent_concepts_child_concept_id_concept_id_fk FOREIGN KEY (child_concept_id) REFERENCES t_concept(concept_id) DEFERRABLE INITIALLY DEFERRED
--;;
ALTER TABLE t_cached_parent_concepts ADD CONSTRAINT t_cached_parent_concepts_parent_concept_id_concept_id_fk FOREIGN KEY (parent_concept_id) REFERENCES t_concept(concept_id) DEFERRABLE INITIALLY DEFERRED
--;;
ALTER TABLE t_cross_map_table ADD CONSTRAINT t_cross_map_table_concept_id_concept_id_fk FOREIGN KEY (concept_id) REFERENCES t_concept(concept_id) DEFERRABLE INITIALLY DEFERRED
--;;
ALTER TABLE t_description ADD CONSTRAINT t_description_concept_id_concept_id_fk FOREIGN KEY (concept_id) REFERENCES t_concept(concept_id) DEFERRABLE INITIALLY DEFERRED
--;;
ALTER TABLE t_diagnosis ADD CONSTRAINT t_diagnosis_concept_fk_concept_id_fk FOREIGN KEY (concept_fk) REFERENCES t_concept(concept_id) DEFERRABLE INITIALLY DEFERRED
--;;
ALTER TABLE t_document ADD CONSTRAINT t_document_concept_fk_concept_id_fk FOREIGN KEY (concept_fk) REFERENCES t_concept(concept_id) DEFERRABLE INITIALLY DEFERRED
--;;
ALTER TABLE t_employment ADD CONSTRAINT t_employment_occupation_concept_fk_concept_id_fk FOREIGN KEY (occupation_concept_fk) REFERENCES t_concept(concept_id) DEFERRABLE INITIALLY DEFERRED
--;;
ALTER TABLE t_encounter_template ADD CONSTRAINT t_encounter_template_concept_fk_concept_id_fk FOREIGN KEY (concept_fk) REFERENCES t_concept(concept_id) DEFERRABLE INITIALLY DEFERRED
--;;
ALTER TABLE t_encounter_template ADD CONSTRAINT t_encounter_template_encounter_concept_fk_concept_id_fk FOREIGN KEY (encounter_concept_fk) REFERENCES t_concept(concept_id) DEFERRABLE INITIALLY DEFERRED
--;;
ALTER TABLE t_epilepsy_term ADD CONSTRAINT t_epilepsy_term_concept_fk_concept_id_fk FOREIGN KEY (concept_fk) REFERENCES t_concept(concept_id) DEFERRABLE INITIALLY DEFERRED
--;;
ALTER TABLE t_form_epilepsy_surgery_mdt ADD CONSTRAINT t_form_epilepsy_surgery_mdt_handedness_concept_fk_concept_id_fk FOREIGN KEY (handedness_concept_fk) REFERENCES t_concept(concept_id) DEFERRABLE INITIALLY DEFERRED
--;;
ALTER TABLE t_form_item_result ADD CONSTRAINT t_form_item_result_concept_value_fk_concept_id_fk FOREIGN KEY (concept_value_fk) REFERENCES t_concept(concept_id) DEFERRABLE INITIALLY DEFERRED
--;;
ALTER TABLE t_form_presenting_complaints_concepts ADD CONSTRAINT t_form_presenting_complaints_concepts_presenting_complaint_conc FOREIGN KEY (presenting_complaint_concept_fk) REFERENCES t_concept(concept_id) DEFERRABLE INITIALLY DEFERRED
--;;
ALTER TABLE t_form_presenting_complaints ADD CONSTRAINT t_form_presenting_complaints_main_presenting_complaint_concept_ FOREIGN KEY (main_presenting_complaint_concept_fk) REFERENCES t_concept(concept_id) DEFERRABLE INITIALLY DEFERRED
--;;
ALTER TABLE t_form_problems_concepts ADD CONSTRAINT t_form_problems_concepts_conceptconceptid_concept_id_fk FOREIGN KEY (conceptconceptid) REFERENCES t_concept(concept_id) DEFERRABLE INITIALLY DEFERRED
--;;
ALTER TABLE t_form_procedure_botulinum_toxin ADD CONSTRAINT t_form_procedure_botulinum_toxin_indication_concept_fk_concept_ FOREIGN KEY (indication_concept_fk) REFERENCES t_concept(concept_id) DEFERRABLE INITIALLY DEFERRED
--;;
ALTER TABLE t_form_procedure_generic ADD CONSTRAINT t_form_procedure_generic_procedure_concept_fk_concept_id_fk FOREIGN KEY (procedure_concept_fk) REFERENCES t_concept(concept_id) DEFERRABLE INITIALLY DEFERRED
--;;
ALTER TABLE t_result_eeg_background_findings ADD CONSTRAINT t_form_result_eeg_background_findings_concept_fk_concept_id_fk FOREIGN KEY (concept_fk) REFERENCES t_concept(concept_id) DEFERRABLE INITIALLY DEFERRED
--;;
ALTER TABLE t_result_eeg_locations ADD CONSTRAINT t_form_result_eeg_locations_conceptconceptid_concept_id_fk FOREIGN KEY (concept_fk) REFERENCES t_concept(concept_id) DEFERRABLE INITIALLY DEFERRED
--;;
ALTER TABLE t_result_eeg_ictal_findings ADD CONSTRAINT t_form_result_ictal_findings_concept_fk_concept_id_fk FOREIGN KEY (concept_fk) REFERENCES t_concept(concept_id) DEFERRABLE INITIALLY DEFERRED
--;;
ALTER TABLE t_medication_event ADD CONSTRAINT t_medication_event_event_concept_fk_concept_id_fk FOREIGN KEY (event_concept_fk) REFERENCES t_concept(concept_id) DEFERRABLE INITIALLY DEFERRED
--;;
ALTER TABLE t_medication ADD CONSTRAINT t_medication_medication_concept_fk_concept_id_fk FOREIGN KEY (medication_concept_fk) REFERENCES t_concept(concept_id) DEFERRABLE INITIALLY DEFERRED
--;;
ALTER TABLE t_metabolic_enzyme_application ADD CONSTRAINT t_metabolic_enzyme_application_drug_concept_fk_concept_id_fk FOREIGN KEY (drug_concept_fk) REFERENCES t_concept(concept_id) DEFERRABLE INITIALLY DEFERRED
--;;
ALTER TABLE t_metabolic_enzyme_eligibility ADD CONSTRAINT t_metabolic_enzyme_eligibility_diagnosis_concept_fk_concept_id_ FOREIGN KEY (diagnosis_concept_fk) REFERENCES t_concept(concept_id) DEFERRABLE INITIALLY DEFERRED
--;;
ALTER TABLE t_metabolic_enzyme_eligibility ADD CONSTRAINT t_metabolic_enzyme_eligibility_drug_concept_fk_concept_id_fk FOREIGN KEY (drug_concept_fk) REFERENCES t_concept(concept_id) DEFERRABLE INITIALLY DEFERRED
--;;
ALTER TABLE t_ms_diagnosis ADD CONSTRAINT t_ms_diagnosis_concept_fk_concept_id_fk FOREIGN KEY (concept_fk) REFERENCES t_concept(concept_id) DEFERRABLE INITIALLY DEFERRED
--;;
ALTER TABLE t_multiple_sclerosis_dmt ADD CONSTRAINT t_multiple_sclerosis_dmt_concept_fk_concept_id_fk FOREIGN KEY (concept_fk) REFERENCES t_concept(concept_id) DEFERRABLE INITIALLY DEFERRED
--;;
ALTER TABLE t_patient ADD CONSTRAINT t_patient_country_of_birth_concept_fk_concept_id_fk FOREIGN KEY (country_of_birth_concept_fk) REFERENCES t_concept(concept_id) DEFERRABLE INITIALLY DEFERRED
--;;
ALTER TABLE t_patient ADD CONSTRAINT t_patient_ethnic_origin_concept_fk_concept_id_fk FOREIGN KEY (ethnic_origin_concept_fk) REFERENCES t_concept(concept_id) DEFERRABLE INITIALLY DEFERRED
--;;
ALTER TABLE t_patient ADD CONSTRAINT t_patient_highest_educational_level_concept_fk_concept_id_fk FOREIGN KEY (highest_educational_level_concept_fk) REFERENCES t_concept(concept_id) DEFERRABLE INITIALLY DEFERRED
--;;
ALTER TABLE t_patient ADD CONSTRAINT t_patient_occupation_concept_fk_concept_id_fk FOREIGN KEY (occupation_concept_fk) REFERENCES t_concept(concept_id) DEFERRABLE INITIALLY DEFERRED
--;;
ALTER TABLE t_patient ADD CONSTRAINT t_patient_racial_group_concept_fk_concept_id_fk FOREIGN KEY (racial_group_concept_fk) REFERENCES t_concept(concept_id) DEFERRABLE INITIALLY DEFERRED
--;;
ALTER TABLE t_project_concept ADD CONSTRAINT t_project_concept_conceptconceptid_concept_id_fk FOREIGN KEY (conceptconceptid) REFERENCES t_concept(concept_id) DEFERRABLE INITIALLY DEFERRED
--;;
ALTER TABLE t_project_recruitment_concept ADD CONSTRAINT t_project_recruitment_concept_conceptconceptid_concept_id_fk FOREIGN KEY (conceptconceptid) REFERENCES t_concept(concept_id) DEFERRABLE INITIALLY DEFERRED
--;;
ALTER TABLE t_project ADD CONSTRAINT t_project_specialty_concept_fk_concept_id_fk FOREIGN KEY (specialty_concept_fk) REFERENCES t_concept(concept_id) DEFERRABLE INITIALLY DEFERRED
--;;
ALTER TABLE t_relationship ADD CONSTRAINT t_relationship_relationship_type_concept_id_concept_id_fk FOREIGN KEY (relationship_type_concept_id) REFERENCES t_concept(concept_id) DEFERRABLE INITIALLY DEFERRED
--;;
ALTER TABLE t_relationship ADD CONSTRAINT t_relationship_source_concept_id_concept_id_fk FOREIGN KEY (source_concept_id) REFERENCES t_concept(concept_id) DEFERRABLE INITIALLY DEFERRED
--;;
ALTER TABLE t_relationship ADD CONSTRAINT t_relationship_target_concept_id_concept_id_fk FOREIGN KEY (target_concept_id) REFERENCES t_concept(concept_id) DEFERRABLE INITIALLY DEFERRED
--;;
