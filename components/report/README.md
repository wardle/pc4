pc4: report 
==========

This module produces reports. 

It is designed to replace the legacy rsdb module that created a PDF artefact from an encounter. 

This module's only responsibility is to generate documents. 

This module specifically does not:

- depend on the underlying database
- depend on any specific model of synchronous or asychronous processing
- do anything with the PDF generated

Consequently, this module is *pure*. It could be used for arbitrary data, in a background job processing system and resulting output(s) be sent via email or stored in multiple document repositories. 

The legacy model is that a document/report is tied to an encounter, and is based on content within that encounter. 

This module will initially adopt a similar approach, but use of the legacy application has demonstrated a need to also potentially include data from a range of encounters, such as encounters spanning a whole episode of care. This would make it possible to use this module to generate, for example, a discharge summary, or the results of a defined physiotherapy intervention that spanned multiple encounters and referenced outcome measures at the start, and throughout the episode, such as graphically or in tabular form. 


