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
 * language (Greek, Hebrew, Thai, Korean's Hangul, Japanese kana, ...) is that language; otherwise
 * the language whose most common short words (articles, prepositions, conjunctions) make up the
 * largest part of the text, among sixteen European languages. Function words are a large share of
 * any real text, so this is reliable for a paragraph or more and says nothing (null) for a list,
 * a table of numbers or a few words. Tika's own n-gram detector was tried first and named Spanish
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
        List<String> words = List.of(sample.toLowerCase(Locale.ROOT).split("[^\\p{L}]+"));
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
                case ARABIC -> "ar";
                case THAI -> "th";
                case HANGUL -> "ko";
                case HIRAGANA, KATAKANA -> "ja";
                case HAN -> "zh";
                case DEVANAGARI -> "hi";
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
                return e.getKey();
            }
        }
        return null;
    }
}
