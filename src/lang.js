// Language registry + per-language vocabulary. Everything here is data: adding a language means adding one entry
// (see "Adding a language" in the README). STT/TTS codes come from docs.gnani.ai (Prisma v3.0: 10 codes, Timbre v3.0: same 10).
//
// hi = Hindi / Hinglish (the original, benchmarked pipeline: Prisma `codemix`, Timbre `hi-en`).
// Every other language uses Prisma's `transcribe` format, which returns amounts as digits ("500 रुपये"), so
// money never depends on parsing a spoken number word in that language. Verified live for all ten (see docs/languages.md).
export const LANGS = {
  hi: { label: 'हिन्दी · Hinglish', stt: 'hi-IN', format: 'codemix', tts: 'hi-en', voice: 'Shivani', speech: 'hi-IN' },
  en: { label: 'English', stt: 'en-IN', format: 'transcribe', tts: 'en-IN', voice: 'Sohini', speech: 'en-IN' },
  kn: { label: 'ಕನ್ನಡ', stt: 'kn-IN', format: 'transcribe', tts: 'kn-IN', voice: 'Shruthi', speech: 'kn-IN' },
  mr: { label: 'मराठी', stt: 'mr-IN', format: 'transcribe', tts: 'mr-IN', voice: 'Anitasingh', speech: 'mr-IN' },
  ta: { label: 'தமிழ்', stt: 'ta-IN', format: 'transcribe', tts: 'ta-IN', voice: 'Kalpana', speech: 'ta-IN' },
  te: { label: 'తెలుగు', stt: 'te-IN', format: 'transcribe', tts: 'te-IN', voice: 'Kalpana', speech: 'te-IN' },
  gu: { label: 'ગુજરાતી', stt: 'gu-IN', format: 'transcribe', tts: 'gu-IN', voice: 'Shivani', speech: 'gu-IN' },
  bn: { label: 'বাংলা', stt: 'bn-IN', format: 'transcribe', tts: 'bn-IN', voice: 'Shivani', speech: 'bn-IN' },
  ml: { label: 'മലയാളം', stt: 'ml-IN', format: 'transcribe', tts: 'ml-IN', voice: 'Rajni', speech: 'ml-IN' },
  pa: { label: 'ਪੰਜਾਬੀ', stt: 'pa-IN', format: 'transcribe', tts: 'pa-IN', voice: 'Chetana', speech: 'pa-IN' },
};
// "Auto": Prisma identifies the language itself (indic-auto) and tells us which one it heard.
export const AUTO = { label: 'Auto-detect', stt: 'indic-auto', format: 'transcribe' };

export const langOf = (code) => (LANGS[code] ? code : 'hi');
// Gnani language code (kn-IN) -> our short code (kn). Unknown or empty -> null.
export const fromGnani = (c) => Object.keys(LANGS).find((k) => LANGS[k].stt === c) || null;

const SCRIPTS = [['kn', /[ಀ-೿]/], ['te', /[ఀ-౿]/], ['ta', /[஀-௿]/], ['ml', /[ഀ-ൿ]/],
  ['bn', /[ঀ-৿]/], ['gu', /[઀-૿]/], ['pa', /[਀-੿]/]];
// Which language is this text in? Devanagari is shared by Hindi and Marathi, so the user's choice decides between them.
export function detectLang(text, preferred = 'hi') {
  for (const [k, re] of SCRIPTS) if (re.test(text)) return k;
  if (/[ऀ-ॿ]/.test(text)) return preferred === 'mr' ? 'mr' : 'hi';
  return langOf(preferred);
}

// ---- vocabulary for native-script languages. Prefix ("stem") matching, because these languages glue endings onto words
// (Kannada ಕೊಟ್ಟರು, ಕೊಟ್ಟಿದ್ದಾರೆ, ಕೊಟ್ಟೆ all start with ಕೊಟ್ಟ). Latin/Hindi words stay in parser.js with exact matching.
export const STEMS = {
  credit: [
    'उधार', 'लिहा', 'लिही', 'नावे', 'बाकी', 'घेतल', 'घेतले',            // mr (and shared)
    'ಸಾಲ', 'ಬರೆ', 'ಉದ್ದಾರ', 'ಬಾಕಿ', 'ತೆಗೆದುಕೊಂಡ',                      // kn
    'అప్పు', 'బాకీ', 'రాయ', 'తీసుకున్న',                                   // te
    'கடன்', 'பாக்கி', 'எழுது', 'வாங்கி',                                   // ta
    'ઉધાર', 'ઉધારી', 'બાકી', 'લખો', 'લખ', 'લીધ',                          // gu
    'বাকি', 'ধার', 'উধার', 'লিখ', 'নিয়েছ',                                // bn
    'കടം', 'ബാക്കി', 'എഴുത', 'വാങ്ങി',                                     // ml
    'ਉਧਾਰ', 'ਬਾਕੀ', 'ਲਿਖੋ', 'ਲਿਖ', 'ਲਿਆ',                                  // pa
  ],
  payment: [
    'दिले', 'दिला', 'दिली', 'भरल', 'परत', 'मिळाल', 'चुकत',                 // mr
    'ಕೊಟ್ಟ', 'ಜಮಾ', 'ಪಾವತಿ', 'ಕಟ್ಟಿ', 'ಹಿಂದಿರುಗಿ', 'ಕೊಡ',                  // kn
    'ఇచ్చ', 'జమ', 'చెల్లించ', 'తిరిగి', 'కట్టా',                           // te
    'கொடுத்த', 'செலுத்த', 'கட்டி', 'திரும்ப', 'வரவு',                     // ta
    'આપ્ય', 'આપ્યા', 'જમા', 'ચૂકવ', 'પાછા',                                // gu
    'দিয়েছ', 'দিল', 'জমা', 'শোধ', 'ফেরত',                                 // bn
    'കൊടുത്ത', 'തന്ന', 'അടച്ച', 'ജമ', 'തിരിച്ച',                          // ml
    'ਦਿੱਤ', 'ਜਮ੍ਹਾ', 'ਜਮਾ', 'ਵਾਪਸ', 'ਚੁਕਾ',                                // pa
  ],
  // A payment word that can only mean money coming back ("jama"), as opposed to "gave" which also means "gave credit".
  strongPay: ['जमा', 'परत', 'भरल', 'मिळाल', 'ಜಮಾ', 'ಪಾವತಿ', 'ಕಟ್ಟಿ', 'ಹಿಂದಿರುಗಿ', 'జమ', 'చెల్లించ', 'తిరిగి', 'செலுத்த', 'கட்டி', 'திரும்ப', 'வரவு',
    'જમા', 'ચૂકવ', 'પાછા', 'জমা', 'শোধ', 'ফেরত', 'അടച്ച', 'ജമ', 'തിരിച്ച', 'ਜਮ੍ਹਾ', 'ਜਮਾ', 'ਵਾਪਸ', 'ਚੁਕਾ'],
  query: ['किती', 'एकूण', 'बॅलन्स', 'शिल्लक', 'सांगा', 'ಎಷ್ಟು', 'ಒಟ್ಟು', 'ಬ್ಯಾಲೆನ್ಸ್', 'ಲೆಕ್ಕ', 'ಹೇಳಿ', 'ఎంత', 'మొత్తం', 'బ్యాలెన్స్', 'చెప్పండి',
    'எவ்வளவு', 'மொத்தம்', 'இருப்பு', 'சொல்லுங்கள்', 'કેટલા', 'કેટલું', 'કુલ', 'બેલેન્સ', 'હિસાબ', 'কত', 'মোট', 'ব্যালেন্স', 'হিসাব',
    'എത്ര', 'ആകെ', 'ബാലൻസ്', 'പറയൂ', 'ਕਿੰਨ', 'ਕੁੱਲ', 'ਬੈਲੇਂਸ', 'ਹਿਸਾਬ'],
  // question words: "how much" in each language. Unlike "total"/"balance", these make a sentence a query even when it contains an amount.
  queryStrong: ['किती', 'ಎಷ್ಟು', 'ఎంత', 'எவ்வளவு', 'કેટલ', 'কত', 'എത്ര', 'ਕਿੰਨ'],
  correction: ['नाही', 'चुकी', 'चूक', 'नको', 'ಇಲ್ಲ', 'ತಪ್ಪು', 'కాదు', 'తప్పు', 'இல்லை', 'தவறு', 'નહીં', 'ખોટું', 'না', 'ভুল', 'അല്ല', 'തെറ്റ്', 'ਨਹੀਂ', 'ਗਲਤ'],
  // "the other one" ("nahi, doosra Rahul") moves the last entry to the other customer with the same first name.
  other: ['doosra', 'dusra', 'dusre', 'other', 'दूसरा', 'दुसरा', 'दूसरे', 'दुसरे', 'ಇನ್ನೊಬ್ಬ', 'ಬೇರೆ', 'మరొక', 'வேறு', 'மற்ற', 'બીજ', 'অন্য', 'മറ്റ', 'ਦੂਜ'],
  yesterday: ['काल', 'कालच', 'ನಿನ್ನೆ', 'నిన్న', 'நேற்று', 'ગઈકાલે', 'ഇന്നലെ'], // exact tokens, not prefixes (a customer called Kaliya must not become "yesterday")
  today: ['ಇಂದು', 'ఈరోజు', 'இன்று', 'આજે', 'ഇന്ന്'],
};

// Words stuck to the end of a name ("रमेशला", "ರಮೇಶನಿಗೆ", "ரமேஷுக்கு"): case endings. Longest first. Only applied when the
// active language is not Hindi/English, so Hindi names that happen to end in ला/ना (कमला) are never clipped.
export const NAME_SUFFIXES = [
  'च्या', 'कडे', 'साठी', 'पासून', 'ला', 'ने', 'चे', 'चा', 'ची', 'ना', 'ही',            // mr
  'ನಿಗೆ', 'ನಿಂದ', 'ಇಗೆ', 'ಅವರಿಗೆ', 'ರಿಗೆ', 'ಗೆ', 'ನು', 'ಅವರ',                                                                  // kn
  'గారికి', 'గారు', 'కి', 'కు', 'ను', 'తో', 'నుండి', 'యొక్క', 'ని',                                                                              // te
  'ுக்கு', 'க்கு', 'ிடம்', 'இடம்', 'ின்', 'உடைய', 'ோடு',                                                                                    // ta
  'ને', 'નો', 'ની', 'નું', 'ના', 'પાસે', 'થી', 'એ',                                                                                              // gu
  'কে', 'এর', 'ের', 'দের', 'থেকে',                                                                                                         // bn
  'ിന്റെ', 'യുടെ', 'ിന്', 'ിനെ', 'ോട്', 'ക്ക്', 'ന്',                                                                                              // ml
  'ਨੂੰ', 'ਦਾ', 'ਦੀ', 'ਦੇ', 'ਨੇ', 'ਕੋਲੋਂ', 'ਤੋਂ',                                                                                                   // pa
].sort((a, b) => b.length - a.length);

// Separate-word postpositions / honorifics in these languages ("ರಮೇಶ್ ಅವರಿಗೆ", "రమేష్ కి", "रमेश यांना").
export const POSTPOS_EXTRA = ['ಗೆ', 'ಅವರಿಗೆ', 'ಅವರ', 'ಅವರು', 'ಇಂದ', 'यांना', 'यांनी', 'यांचे', 'यांचा', 'कडून', 'ला', 'चे', 'चा', 'ची',
  'కి', 'కు', 'గారికి', 'గారి', 'ను', 'க்கு', 'இடம்', 'ને', 'નો', 'ની', 'કે', 'কে', 'এর', 'ের', 'ന്', 'ਨੂੰ', 'ਦਾ', 'ਦੀ', 'ਦੇ', 'ਨੇ'];
export const HONOR_EXTRA = ['ಅವರು', 'ಅವರ', 'ಅವರಿಗೆ', 'गारु', 'यांना', 'గారు', 'గారికి', 'ஐயா', 'அவர்', 'ભાઈ', 'ભાઈને', 'দা', 'ভাই', 'ചേട്ടൻ', 'ചേച്ചി', 'ਜੀ', 'ਭਾਈ', 'ताई', 'दादा', 'काका', 'अण्णा', 'ಅಣ್ಣ', 'ಅಕ್ಕ', 'అన్న', 'అక్క', 'அண்ணா', 'அக்கா'];

// Words that are never a customer name: currency, "and", and filler in the regional languages.
export const STOP_EXTRA = ['ರೂಪಾಯಿ', 'ರೂ', 'रुपये', 'रुपया', 'रुपयांचे', 'रुपयांना', 'రూపాయలు', 'రూపాయి', 'రూపాయల', 'ரூபாய்', 'ரூபாயை', 'રૂપિયા', 'રૂપિયાનો', 'টাকা', 'টাকার', 'രൂപ', 'ਰੁਪਏ',
  'आणि', 'ಮತ್ತು', 'మరియు', 'మరి', 'மற்றும்', 'અને', 'এবং', 'আর', 'ਅਤੇ', 'आहे', 'आहेत', 'ಇದೆ', 'ಆಗಿದೆ', 'ఉంది', 'உள்ளது', 'છે', 'আছে', 'ഉണ്ട്', 'ਹੈ', 'ਹਨ',
  // English pronouns and verbs: "she paid 500" must carry over to the last customer, never create a customer called "She".
  'she', 'he', 'they', 'him', 'her', 'them', 'his', 'hers', 'their', 'took', 'take', 'taken', 'borrowed', 'owes', 'owe', 'owed', 'gave', 'give', 'given', 'paid', 'pay', 'pays',
  'me', 'my', 'mine', 'you', 'your', 'it', 'its', 'that', 'this', 'these', 'those', 'also', 'again', 'back', 'nothing', 'something', 'today', 'yesterday', 'tomorrow',
  'add', 'added', 'adding', 'remove', 'cancel', 'please', 'dont', 'don', 't', 's', 'not', 'never', 'mat', 'account', 'accounts', 'khata', 'cash', 'only', 'just', 'from', 'on', 'in', 'at', 'with', 'by', 'as', 'a', 'an',
  'paune', 'sava', 'saade', 'parson', 'lekin', 'magar', 'but', 'however', 'sirf', 'abhi'];
export const AND_WORDS = ['आणि', 'ಮತ್ತು', 'మరియు', 'మరి', 'மற்றும்', 'અને', 'এবং', 'আর', 'ਅਤੇ'];
