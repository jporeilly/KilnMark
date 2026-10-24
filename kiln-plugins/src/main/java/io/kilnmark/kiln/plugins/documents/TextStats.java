package io.kilnmark.kiln.plugins.documents;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Words and language of a document's text.
 *
 * <p>Language is found without a library: a text written mostly in a script that belongs to one
 * language (Greek, Hebrew, Thai, Korean's Hangul, Japanese kana, ...) is that language; Arabic
 * script is Arabic, Persian or Urdu only when letters only one of them uses say so, and Devanagari
 * Hindi, Marathi or Nepali only when their common words do; otherwise
 * the language whose most common short words (articles, prepositions, conjunctions) make up the
 * largest part of the text, among sixteen European languages and Turkish. Function words are a
 * large share of any real text, so this is reliable for a paragraph or more and says nothing
 * (null) for a list, a table of numbers or a few words. Tika's own n-gram detector was tried first and named Spanish
 * text Catalan (see ADR 0017).
 */
public final class TextStats {

    /** Words needed before a language is named. */
    static final int MIN_WORDS = 20;

    /** Characters looked at. */
    static final int SAMPLE = 20_000;

    private static final Map<String, Set<String>> WORDS = Map.ofEntries(
        Map.entry("en", Set.of("the", "and", "of", "to", "in", "is", "that", "for", "it", "with", "as", "was", "on",
            "are", "be", "this", "by", "have", "from", "or", "not", "but", "at", "they", "which", "you", "will", "an",
            "their", "we", "has", "were", "been", "can", "would", "should", "our", "your", "there", "what")),
        Map.entry("fr", Set.of("le", "la", "les", "et", "des", "du", "un", "une", "est", "que", "qui", "dans", "pour",
            "pas", "sur", "au", "avec", "ce", "ces", "sont", "par", "plus", "nous", "vous", "il", "elle", "ils", "mais",
            "ou", "leur", "être", "aux", "cette", "de", "en", "ne", "se", "son", "sa")),
        Map.entry("de", Set.of("der", "die", "das", "und", "ist", "nicht", "ein", "eine", "zu", "den", "mit", "von",
            "sich", "des", "auf", "für", "im", "dem", "auch", "es", "werden", "aus", "er", "sie", "wir", "ich", "bei",
            "nach", "wird", "sind", "oder", "noch", "wie", "einer", "über", "dass", "kann", "zum", "zur")),
        Map.entry("es", Set.of("el", "la", "los", "las", "de", "y", "que", "en", "un", "una", "es", "por", "con",
            "para", "no", "se", "del", "al", "lo", "como", "más", "pero", "sus", "su", "le", "ya", "este", "esta",
            "son", "hasta", "desde", "cuando", "muy", "sin", "sobre", "también", "entre", "hay")),
        Map.entry("it", Set.of("il", "la", "di", "che", "e", "un", "una", "per", "non", "sono", "del", "della", "le",
            "gli", "con", "si", "da", "al", "nel", "ma", "come", "anche", "più", "alla", "dei", "questo", "questa",
            "è", "essere", "ha", "hanno", "delle", "nella", "lo", "ci", "sul")),
        Map.entry("pt", Set.of("o", "a", "os", "as", "de", "que", "e", "do", "da", "em", "um", "uma", "para", "com",
            "não", "por", "mais", "dos", "das", "se", "na", "no", "ao", "como", "mas", "foi", "ele", "ela", "são",
            "também", "pelo", "pela", "você", "isso", "muito", "seu", "sua")),
        Map.entry("nl", Set.of("de", "het", "een", "en", "van", "is", "dat", "niet", "in", "op", "te", "zijn", "met",
            "voor", "er", "maar", "ook", "aan", "als", "bij", "door", "worden", "wordt", "nog", "naar", "dit", "deze",
            "kan", "hebben", "heeft", "uit", "zij", "wij", "ik", "je", "om", "wat")),
        Map.entry("sv", Set.of("och", "att", "det", "som", "en", "är", "på", "för", "med", "av", "inte", "till",
            "den", "har", "de", "om", "ett", "var", "jag", "men", "så", "han", "hon", "vi", "kan", "från", "eller",
            "när", "ska", "också", "vara", "sig", "efter")),
        Map.entry("da", Set.of("og", "at", "det", "er", "en", "til", "på", "med", "for", "af", "ikke", "den", "har",
            "de", "som", "et", "var", "jeg", "men", "så", "han", "hun", "vi", "kan", "fra", "eller", "når", "skal",
            "også", "der", "være", "sig", "efter", "hvis")),
        Map.entry("no", Set.of("og", "at", "det", "er", "en", "til", "på", "med", "for", "av", "ikke", "den", "har",
            "de", "som", "et", "var", "jeg", "men", "så", "han", "hun", "vi", "kan", "fra", "eller", "når", "skal",
            "også", "ble", "være", "seg", "etter", "hvis")),
        Map.entry("fi", Set.of("ja", "on", "ei", "se", "että", "hän", "oli", "ovat", "kuin", "mutta", "tai", "joka",
            "jos", "myös", "sekä", "kun", "niin", "ole", "olla", "vain", "tämä", "siitä", "mukaan", "sen", "hänen",
            "voi", "nyt")),
        Map.entry("pl", Set.of("i", "w", "na", "z", "nie", "się", "do", "to", "że", "jest", "o", "jak", "ale", "po",
            "co", "tak", "za", "od", "przez", "są", "dla", "może", "jego", "już", "oraz", "tylko", "przy", "które",
            "który", "była", "być")),
        Map.entry("cs", Set.of("a", "v", "se", "na", "je", "že", "to", "s", "z", "do", "o", "jako", "ale", "pro",
            "by", "jsou", "tak", "jeho", "které", "který", "nebo", "však", "jsem", "být", "byl", "bylo", "také",
            "podle", "už")),
        Map.entry("ru", Set.of("и", "в", "не", "на", "что", "с", "по", "это", "как", "к", "но", "из", "у", "за",
            "от", "о", "так", "для", "же", "все", "он", "она", "они", "мы", "вы", "бы", "был", "была", "или",
            "его", "только")),
        Map.entry("uk", Set.of("і", "в", "не", "на", "що", "з", "до", "це", "як", "та", "але", "у", "за", "від",
            "про", "так", "для", "же", "все", "він", "вона", "вони", "ми", "ви", "був", "була", "або", "є",
            "його", "тільки")),
        Map.entry("ca", Set.of("els", "les", "amb", "per", "una", "del", "que", "és", "dels", "pel", "pels", "són",
            "però", "també", "aquesta", "aquest", "fins", "seva", "seu", "molt", "i", "la", "el", "de", "al", "no",
            "més", "com", "l", "d")),
        Map.entry("tr", Set.of("ve", "bir", "bu", "da", "de", "için", "ile", "olan", "çok", "daha", "gibi", "ama",
            "ne", "o", "sonra", "kadar", "olarak", "en", "her", "mi", "var", "yok")));

    private TextStats() {
    }

    /** Runs of letters or digits. */
    public static long words(String text) {
        long n = 0;
        boolean in = false;
        for (int i = 0; i < text.length(); i++) {
            boolean w = Character.isLetterOrDigit(text.charAt(i));
            if (w && !in) {
                n++;
            }
            in = w;
        }
        return n;
    }

    /** The ISO 639-1 code of the text's language, for example {@code en}, or null when unsure. */
    public static String language(String text) {
        String sample = text.length() > SAMPLE ? text.substring(0, SAMPLE) : text;
        String script = script(sample);
        if (script != null) {
            return script;
        }
        List<String> words = List.of(sample.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{M}]+"));
        int total = 0;
        Map<String, Integer> hits = new HashMap<>();
        for (String w : words) {
            if (w.isEmpty()) {
                continue;
            }
            total++;
            for (var e : WORDS.entrySet()) {
                if (e.getValue().contains(w)) {
                    hits.merge(e.getKey(), 1, Integer::sum);
                }
            }
        }
        if (total < MIN_WORDS) {
            return null;
        }
        String best = null;
        int top = 0;
        int second = 0;
        for (var e : hits.entrySet()) {
            int n = e.getValue();
            if (n > top || (n == top && best != null && e.getKey().compareTo(best) < 0)) {
                second = Math.max(second, top);
                top = n;
                best = e.getKey();
            } else if (n > second) {
                second = n;
            }
        }
        // function words are a fifth or more of real prose; a clear lead over the next language
        if (best == null || top * 8 < total || top * 10 < second * 11) {
            return null;
        }
        return best;
    }

    /** The language of a text mostly in one language's own script, or null. */
    private static String script(String s) {
        Map<String, Integer> counts = new HashMap<>();
        int letters = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (!Character.isLetter(cp)) {
                continue;
            }
            letters++;
            String lang = switch (Character.UnicodeScript.of(cp)) {
                case GREEK -> "el";
                case HEBREW -> "he";
                case ARABIC -> "arabic-script";
                case THAI -> "th";
                case HANGUL -> "ko";
                case HIRAGANA, KATAKANA -> "ja";
                case HAN -> "zh";
                case DEVANAGARI -> "devanagari";
                case ARMENIAN -> "hy";
                case GEORGIAN -> "ka";
                default -> null;
            };
            if (lang != null) {
                counts.merge(lang, 1, Integer::sum);
            }
        }
        if (letters < 20) {
            return null;
        }
        int kana = counts.getOrDefault("ja", 0);
        int han = counts.getOrDefault("zh", 0);
        if (kana > 0 && kana + han > letters / 2) {
            return "ja"; // Japanese mixes kana with Han characters
        }
        for (var e : counts.entrySet()) {
            if (e.getValue() > letters / 2) {
                return switch (e.getKey()) {
                    case "arabic-script" -> arabicScript(s);
                    case "devanagari" -> best(s, DEVANAGARI_WORDS);
                    default -> e.getKey();
                };
            }
        }
        return null;
    }

    /** Letters Urdu uses and Persian and Arabic do not. */
    private static final String URDU = "\u0679\u0688\u0691\u06BA\u06D2\u06BE";
    /** Letters Persian (and Urdu) use and Arabic does not, including Persian kaf and yeh. */
    private static final String PERSIAN = "\u067E\u0686\u0698\u06AF\u06A9\u06CC";
    /** Letters Arabic uses and Persian and Urdu do not: ta marbuta, Arabic kaf and yeh, alef maqsura. */
    private static final String ARABIC = "\u0629\u0643\u064A\u0649";

    /** Arabic, Persian or Urdu, from the letters only one of them uses; null when they do not say. */
    private static String arabicScript(String s) {
        int urdu = 0;
        int persian = 0;
        int arabic = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (URDU.indexOf(c) >= 0) {
                urdu++;
            } else if (PERSIAN.indexOf(c) >= 0) {
                persian++;
            } else if (ARABIC.indexOf(c) >= 0) {
                arabic++;
            }
        }
        if (urdu >= 3 && urdu * 2 > arabic) {
            return "ur";
        }
        if (persian >= 3 && urdu == 0 && persian > arabic * 2) {
            return "fa";
        }
        if (arabic >= 3 && persian == 0 && urdu == 0) {
            return "ar";
        }
        return null;
    }

    /** Common short words of the languages written in Devanagari that Kilnmark tells apart. */
    private static final Map<String, Set<String>> DEVANAGARI_WORDS = Map.of(
        "hi", Set.of("है", "हैं", "के", "की", "में", "और", "से", "को", "का", "यह", "नहीं", "था", "पर", "भी"),
        "mr", Set.of("आहे", "आणि", "च्या", "ला", "हे", "या", "आहेत", "नाही", "केले", "होते", "पण", "तर"),
        "ne", Set.of("छ", "र", "मा", "हो", "गर्न", "लागि", "पनि", "छन्", "थियो", "भएको", "गरेको", "हुन्छ"));

    /** The language whose words lead clearly in {@code s}, among {@code languages}; else null. */
    private static String best(String s, Map<String, Set<String>> languages) {
        Map<String, Integer> hits = new HashMap<>();
        int total = 0;
        for (String w : s.split("[^\\p{L}\\p{M}]+")) {
            if (w.isEmpty()) {
                continue;
            }
            total++;
            for (var e : languages.entrySet()) {
                if (e.getValue().contains(w)) {
                    hits.merge(e.getKey(), 1, Integer::sum);
                }
            }
        }
        String top = null;
        int first = 0;
        int second = 0;
        for (var e : hits.entrySet()) {
            if (e.getValue() > first) {
                second = first;
                first = e.getValue();
                top = e.getKey();
            } else if (e.getValue() > second) {
                second = e.getValue();
            }
        }
        if (top == null || total < 8 || first < 3 || first * 10 < second * 15) {
            return null;
        }
        return top;
    }
}
