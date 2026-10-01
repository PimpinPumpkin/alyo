package app.alyo.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Titles {
    /** Default name for a new recording, e.g. "Sep 30 at 7:46 PM". */
    fun forDate(millis: Long): String = SimpleDateFormat("MMM d 'at' h:mm a", Locale.getDefault()).format(Date(millis))

    /**
     * A word or two saying what a recording is about, for naming it: the content words that come
     * up most, with names (words capitalized mid-sentence) counted double. Null when nothing
     * stands out, which is the right answer for a short or rambling recording.
     */
    fun topic(transcript: Transcript): String? {
        val stopwords = STOPWORDS[transcript.language] ?: ENGLISH
        val scores = HashMap<String, Float>()
        val shown = HashMap<String, String>()
        for (segment in transcript.segments) {
            segment.words.forEachIndexed { index, word ->
                val bare = word.text.trim { !it.isLetterOrDigit() }
                val key = bare.lowercase().removeSuffix("'s")
                if (key.length < 4 || key in stopwords || key.any { it.isDigit() }) return@forEachIndexed
                val previous = segment.words.getOrNull(index - 1)?.text
                val name = bare.first().isUpperCase() && previous != null && previous.last() !in ".?!"
                scores[key] = (scores[key] ?: 0f) + if (name) 2f else 1f
                if (name || key !in shown) shown[key] = if (name) bare.removeSuffix("'s") else key.replaceFirstChar { it.uppercase() }
            }
        }
        val top = scores.entries.filter { it.value >= 2f }.sortedWith(compareByDescending<Map.Entry<String, Float>> { it.value }.thenByDescending { it.key.length })
        return top.take(2).mapNotNull { shown[it.key] }.takeIf { it.isNotEmpty() }?.joinToString(", ")
    }

    private val ENGLISH = (
        "about above after again against also always another anyone anything around because been before being below between both cannot could " +
            "does doing done down during each either else even ever every everyone everything from further gets getting give given goes going gone " +
            "gonna gotta have having here hers herself himself however into itself just kind know known like little look looking lots made make " +
            "many maybe mean might more most much must myself need never next none nothing okay once only other ought ours ourselves over " +
            "really right said same says seem seems shall should since some someone something sort still such sure take taken tell than thank thanks " +
            "that their theirs them themselves then there these they thing things think this those though through today together took toward " +
            "under until upon used very want wants well went were what whatever when where whether which while whom whose will with within without " +
            "would yeah year years yours yourself actually basically definitely probably literally first second third last people time times " +
            "start started back come comes came called lets great good thought " +
            // Dates are capitalized like names but say when, not what.
            "january february march april june july august september october november december " +
            "monday tuesday wednesday thursday friday saturday sunday tomorrow yesterday tonight week weeks month months"
        ).split(' ').toHashSet()

    // German capitalizes every noun, so the "capitalized mid-sentence means a name" bonus goes to
    // nouns in general there. That still favors what the talk is about over how it is said.
    private val GERMAN = (
        "aber alle allem allen aller alles also andere anderen anderer anders auch bald beim bereits bevor bitte bloß dabei dadurch dafür " +
            "dagegen daher dahin damals damit danach dann daran darauf daraus darf darin darum darüber dass davon davor dazu deine deinem deinen " +
            "deiner denn dennoch deren derselbe deshalb dessen dich diese diesem diesen dieser dieses doch dort durch eben ebenso eher eigentlich " +
            "eine einem einen einer eines einfach einige einmal etwa etwas euch euer eure falls fast früher ganz ganze ganzen genau gegen gehen " +
            "geht gemacht genug gerade gern gesagt gewesen gibt ging gleich haben habe hast hatte hatten hätte heute hier hinter immer irgendwie " +
            "jede jedem jeden jeder jedes jedoch jemand jene jetzt kann kannst kein keine keinem keinen keiner können könnte kommen kommt lassen " +
            "machen macht manche manchmal mehr mein meine meinem meinen meiner mich möchte muss müssen nach nachdem natürlich neben nein nicht " +
            "nichts noch nämlich oben obwohl oder ohne recht sagen sagt schon sehr sein seine seinem seinen seiner seit selbst sich sicher sind " +
            "sogar solche soll sollen sollte sondern sonst später trotzdem über überhaupt unter viel viele vielen vielleicht voll vom von vor " +
            "wann waren warum weil weiter welche welchem welchen welcher wenig wenn werden wieder will wird wirklich wissen wohl wollen wurde würde " +
            "während zwar zwischen danke bitte genau mal jahr jahre jahren heute morgen gestern woche wochen monat monate " +
            "januar februar märz april juni juli august september oktober november dezember montag dienstag mittwoch donnerstag freitag samstag sonntag"
        ).split(' ').toHashSet()

    private val FRENCH = (
        "alors après assez aucun aucune aussi autre autres avant avec avoir beaucoup bien bonjour cela celle celles celui cependant cette ceux " +
            "chaque chez comme comment contre dans depuis donc dont elle elles encore enfin entre était étaient être faire fait faut fois font " +
            "leur leurs lors lorsque mais même mêmes merci moins notre nous oui parce parfois peut peuvent plus plusieurs pour pourquoi pouvoir " +
            "quand quel quelle quelles quelque quelques quels quoi rien sans selon sera seront ses sont sous souvent suis sur tant tous tout " +
            "toute toutes très trop vers voici voilà vont votre vous vraiment avait avaient avons avez aura auront cette donc ainsi puis " +
            "aujourd'hui demain hier semaine semaines mois année années jour jours temps chose choses " +
            "janvier février mars avril juin juillet août septembre octobre novembre décembre lundi mardi mercredi jeudi vendredi samedi dimanche"
        ).split(' ').toHashSet()

    private val SPANISH = (
        "ahora algo alguien algún alguna algunas algunos allí antes aquí aunque bien bueno cada casi como cómo con contra cosa cosas cual " +
            "cuales cualquier cuando cuándo cuanto desde después donde dónde durante ella ellas ellos entonces entre eran eres esa esas ese eso " +
            "esos esta estaba estaban estamos están estar estas este esto estos estoy fue fueron gracias había habían hace hacer hacia hasta hay " +
            "hemos hola incluso luego más mejor menos mientras mismo mucha muchas mucho muchos muy nada nadie ningún ninguna nos nosotros nuestra " +
            "nuestro nunca otra otras otro otros para pero poco poder porque pues puede pueden qué quien quién quienes quiero sea según ser será " +
            "siempre sido siendo sin sino sobre solo sólo somos son soy también tampoco tan tanto tengo tenemos tener tiene tienen toda todas " +
            "todavía todo todos tras tus una unas uno unos usted ustedes vamos van veces ver vez voy ya " +
            "hoy mañana ayer semana semanas mes meses año años día días tiempo " +
            "enero febrero marzo abril mayo junio julio agosto septiembre octubre noviembre diciembre lunes martes miércoles jueves viernes sábado domingo"
        ).split(' ').toHashSet()

    private val STOPWORDS = mapOf("en" to ENGLISH, "de" to GERMAN, "fr" to FRENCH, "es" to SPANISH)

}
