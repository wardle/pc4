-- components/rsdb/resources/msss-edss.sql
-- :name fetch-msss :? :*
-- :doc Calculate the MSSS for each year of disease duration and for each EDSS
with 
/* A list of patients who have a diagnosis of multiple sclerosis */
pts_with_ms as (
  select t_patient.id as patient_id, date_birth, sex, min(date_onset) as diagnosis_onset from 
  t_diagnosis, t_patient
  where t_patient.id = t_diagnosis.patient_fk
  and concept_fk = any(:v:ms-concept-ids)
  group by t_patient.id,date_birth,sex
),
/* Determine date of first event for all patients, irrespective of diagnosis */
all_patients_date_onset AS (
  select patient_fk as patient_id,msd.name as diagnosis,mse.date_first_event
  from t_summary_multiple_sclerosis ms 
  left join t_ms_diagnosis msd on ms_diagnosis_fk=msd.id 
  left join (select min(date) as date_first_event,summary_multiple_sclerosis_fk 
  from t_ms_event 
  group by summary_multiple_sclerosis_fk) mse on mse.summary_multiple_sclerosis_fk=ms.id
), 
/* Determine date of first event for patients with multiple sclerosis */
pwms_onset as (
  select patient_id,date_first_event from all_patients_date_onset where patient_id in (select patient_id from pts_with_ms)
),
pwms_onset2 as (
  /* Determine date of onset as either the specified date at onset for the diagnosis of MS or their first MS relapse / event, whichever is earliest */
  select pts_with_ms.patient_id, diagnosis_onset, date_first_event, 
  least(diagnosis_onset, date_first_event) as onset,
  date_birth, sex
  from pts_with_ms left join all_patients_date_onset on (all_patients_date_onset.patient_id = pts_with_ms.patient_id)
),
pwms_onset3 as (
  /* And include age at onset so we can subsequently filter out only those patients we want */
  select patient_id, onset, sex,
    date_part('year',age(onset, date_birth)) as age_onset
  from pwms_onset2
),
pwms_onset4 as (
  /* and now filter for only cohort we want */
  select patient_id, onset, sex, age_onset
  from pwms_onset3
  where age_onset > :min-age-onset
    and age_onset < :max-age-onset
    and sex in (:v*:sex)
),
edss as (
  /* EDSS scores from simple form EDSS and EDSS-FS */
  select edss.encounter_fk as encounter_id,edss.edss_score, cast(replace(replace(edss.edss_score,'SCORE',''),'_','.') as numeric) as edss 
  from t_form_edss edss where edss_score != 'SCORE_LESS_THAN_4'
  union all
  select edssfs.encounter_fk as encounter_id, edssfs.edss_score, cast(replace(replace(edssfs.edss_score,'SCORE',''),'_','.') as numeric) 
  from t_form_edss_fs edssfs  where edss_score != 'SCORE_LESS_THAN_4'
),
edss_relapse as (
  /* Filter out any EDSS scores recorded when patient in relapse AND impute a fake EDSS of 10 at the time of death */
  select patient_fk as patient_id, e.date_time, edss.edss from edss 
  inner join t_encounter e on e.id=edss.encounter_id 
  inner join t_encounter_template et on e.encounter_template_fk=et.id
  inner join t_encounter_type type on et.encounter_type_fk=type.id
  left join t_form_ms_relapse relapse on relapse.encounter_fk=e.id 
  where relapse.in_relapse!='true' and type.seen_in_person='true'
  union all
  select id as patient_id, date_death as date_time, 10 as edss from t_patient where date_death is not null and date_death>'1900-01-01'
  order by patient_id,date_time
),
edss_duration as (
  /* and now combine EDSS scores with our list of patients with multiple sclerosis and dates of onset */
  /* Note: we also remove duplicate EDSS scores made in a single year, instead using the lowest EDSS in that year */
  select edss.patient_id, min(edss.edss) as edss, date_part('year',age(edss.date_time, pwms_onset4.onset)) as disease_duration
  from edss_relapse edss left join pwms_onset4 on (edss.patient_id = pwms_onset4.patient_id)
  where pwms_onset4.onset is not null and edss.date_time > pwms_onset4.onset
  group by edss.patient_id, disease_duration
  order by edss.patient_id, disease_duration
),
edss_duration2 as (
  /* The official MSSS takes EDSS scores from -2 to +2 disease duration and calculates ranking of EDSS */
  /* This has the effect of smoothing the scores. This essentially implements this scheme by faking EDSS scores for the disease */
  /* durations for the year and then -2,-1,+1 and +2 years */
  select patient_id, edss, disease_duration from edss_duration
  union all
  select patient_id, edss, disease_duration-1 as disease_duration from edss_duration
  union all
  select patient_id, edss, disease_duration+1 from edss_duration  
  union all
  select patient_id, edss, disease_duration+2 from edss_duration  
  union all
  select patient_id, edss, disease_duration-2 from edss_duration  
),
count_duration as (
  /* get a count of the number of results we have for each year of duration */
  select disease_duration,count(*) as total from edss_duration2 group by disease_duration order by disease_duration
), 
/*
count_number as (
select distinct edss_duration.disease_duration, edss, count(1) over (partition by edss_duration.disease_duration order by edss) as count, total
  from edss_duration inner join count_duration on (count_duration.disease_duration = edss_duration.disease_duration)
),
*/
ranked as (
  select distinct edss_duration2.disease_duration, edss, rank() over (partition by edss_duration2.disease_duration order by edss) as rank, total from edss_duration2
  inner join count_duration on (count_duration.disease_duration = edss_duration2.disease_duration)
),
ranked2 as (
  select disease_duration,edss, rank, lead(rank) over (partition by disease_duration order by edss) as next_rank, total from ranked
),
ranked3 as (
  select disease_duration, edss, rank, 
    case  when next_rank is null then total 
          when next_rank is not null then next_rank
    end as next_rank, total from ranked2 order by disease_duration, edss
)
select disease_duration::int4, edss::float, 10*((rank::float+next_rank::float)/2)/(total::float+1) as msss from ranked3 where disease_duration >= 0
