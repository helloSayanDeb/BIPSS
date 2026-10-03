"""Reviewed domain nouns for the six languages explicitly requested by the user."""
from pathlib import Path
import hashlib,json
ROOT=Path(__file__).resolve().parents[2]
glossaries={
 'hi':{'Home':'होम','Insights':'स्वास्थ्य जानकारी','Coach':'कोच','More':'और','Sleep':'नींद','Recovery':'रिकवरी','Strain':'परिश्रम','Readiness':'तत्परता','Steps':'कदम','Heart rate':'हृदय गति','Blood oxygen':'रक्त में ऑक्सीजन','Skin temperature':'त्वचा का तापमान','Water':'पानी','Light sleep':'हल्की नींद','Deep sleep':'गहरी नींद','Wake':'जागना','Unknown':'अज्ञात'},
 'bn':{'Home':'হোম','Insights':'স্বাস্থ্য তথ্য','Coach':'কোচ','More':'আরও','Sleep':'ঘুম','Recovery':'পুনরুদ্ধার','Strain':'পরিশ্রম','Readiness':'প্রস্তুতি','Steps':'পদক্ষেপ','Heart rate':'হৃদস্পন্দন','Blood oxygen':'রক্তের অক্সিজেন','Skin temperature':'ত্বকের তাপমাত্রা','Water':'জল','Light sleep':'হালকা ঘুম','Deep sleep':'গভীর ঘুম','Wake':'জেগে থাকা','Unknown':'অজানা'},
 'ta':{'Home':'முகப்பு','Insights':'உடல்நலத் தகவல்கள்','Coach':'வழிகாட்டி','More':'மேலும்','Sleep':'தூக்கம்','Recovery':'மீட்பு','Strain':'உடல் உழைப்பு','Readiness':'தயார்நிலை','Steps':'அடிகள்','Heart rate':'இதயத் துடிப்பு','Blood oxygen':'இரத்த ஆக்சிஜன்','Skin temperature':'தோல் வெப்பநிலை','Water':'தண்ணீர்','Light sleep':'லேசான தூக்கம்','Deep sleep':'ஆழ்ந்த தூக்கம்','Wake':'விழிப்பு','Unknown':'தெரியவில்லை'},
 'te':{'Home':'హోమ్','Insights':'ఆరోగ్య సమాచారం','Coach':'కోచ్','More':'మరిన్ని','Sleep':'నిద్ర','Recovery':'కోలుకోవడం','Strain':'శారీరక శ్రమ','Readiness':'సిద్ధత','Steps':'అడుగులు','Heart rate':'గుండె వేగం','Blood oxygen':'రక్తంలో ఆక్సిజన్','Skin temperature':'చర్మ ఉష్ణోగ్రత','Water':'నీరు','Light sleep':'తేలికపాటి నిద్ర','Deep sleep':'గాఢ నిద్ర','Wake':'మెలకువ','Unknown':'తెలియదు'},
 'kn':{'Home':'ಮುಖಪುಟ','Insights':'ಆರೋಗ್ಯ ಮಾಹಿತಿ','Coach':'ಮಾರ್ಗದರ್ಶಕ','More':'ಇನ್ನಷ್ಟು','Sleep':'ನಿದ್ರೆ','Recovery':'ಚೇತರಿಕೆ','Strain':'ದೈಹಿಕ ಶ್ರಮ','Readiness':'ಸಿದ್ಧತೆ','Steps':'ಹೆಜ್ಜೆಗಳು','Heart rate':'ಹೃದಯ ಬಡಿತ','Blood oxygen':'ರಕ್ತದಲ್ಲಿನ ಆಮ್ಲಜನಕ','Skin temperature':'ಚರ್ಮದ ತಾಪಮಾನ','Water':'ನೀರು','Light sleep':'ಹಗುರ ನಿದ್ರೆ','Deep sleep':'ಆಳ ನಿದ್ರೆ','Wake':'ಎಚ್ಚರ','Unknown':'ತಿಳಿದಿಲ್ಲ'},
 'gu':{'Home':'હોમ','Insights':'આરોગ્ય માહિતી','Coach':'કોચ','More':'વધુ','Sleep':'ઊંઘ','Recovery':'પુનઃપ્રાપ્તિ','Strain':'શારીરિક શ્રમ','Readiness':'તૈયારી','Steps':'પગલાં','Heart rate':'હૃદયના ધબકારા','Blood oxygen':'લોહીમાં ઑક્સિજન','Skin temperature':'ત્વચાનું તાપમાન','Water':'પાણી','Light sleep':'હળવી ઊંઘ','Deep sleep':'ગાઢ ઊંઘ','Wake':'જાગવું','Unknown':'અજ્ઞાત'}
}
for lang,words in glossaries.items():
    values={}
    for english,translated in words.items():
        for spelling in {english,english.lower(),english.upper()}:
            values['copy_'+hashlib.sha256(spelling.encode()).hexdigest()[:12]]=translated
    path=ROOT/'tools/localization/overrides'/f'{lang}.json';path.parent.mkdir(parents=True,exist_ok=True)
    path.write_text(json.dumps(values,ensure_ascii=False,indent=2),encoding='utf-8')
print('Reviewed health nouns for',', '.join(glossaries))
