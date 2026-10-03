# Offline Coach guidance

The symptom rules run before both the on-device model and the score reporter.
They give basic self-care and escalation information, never a diagnosis. Sensor
numbers are not used to dismiss symptoms. Text matching is deliberately limited:
this is not a clinical triage system or a replacement for professional assessment.

Reviewed against these primary sources on 30 September 2026:

- [NHS: dizziness](https://www.nhs.uk/symptoms/dizziness/)
- [NHS: vertigo and urgent warning signs](https://www.nhs.uk/conditions/vertigo/)
- [NHS: shortness of breath](https://www.nhs.uk/symptoms/shortness-of-breath/)
- [NHS: chest pain](https://www.nhs.uk/conditions/chest-pain/)
- [NHS: headaches](https://www.nhs.uk/symptoms/headaches/)
- [NHS: fever in adults](https://www.nhs.uk/symptoms/fever-in-adults/)
- [NHS: nausea](https://www.nhs.uk/symptoms/feeling-sick-nausea/)
- [India's Emergency Response Support System](https://112.gov.in/): 112.

UK service numbers in the source guidance are replaced with India's 112 for
emergencies and location-neutral medical advice for non-emergencies. Outside
India, the Coach directs users to their local emergency number. The app never
automatically places a call or sends an SOS from a chat response.

Hydration goals are optional user-selected logging targets, not a fluid
prescription calculated from weight, weather, or sensor values. An empty log is
not evidence of dehydration. Corrections modify today's newest drinks inside
one Room transaction; yesterday's records and timestamps remain intact.
