package dev.antigravity.fluidtransit.data.store

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Scrivere un file piccolo senza poterlo perdere a meta'.
 *
 * I quattro archivi dell'utente — le stelle, le routine, i posti salvati, le
 * ricerche recenti — si salvavano con un `writeText`, che prima **tronca** il
 * file e poi ci scrive dentro. Fra le due cose c'e' una finestra in cui il
 * file esiste ed e' vuoto, o meta'. Se il processo finisce li' dentro — il
 * sistema che chiude un'app in secondo piano e' la normalita', non un
 * incidente — al riavvio il JSON non si legge, il lettore risponde "nessun
 * preferito" perche' e' scritto cosi', e la prima stella toccata dopo
 * riscrive il file: le altre sono sparite per sempre, senza un errore.
 *
 * Nessuno l'ha visto succedere, ed e' proprio il punto: quando succede non
 * lascia tracce, e da fuori sembra che l'app "si sia dimenticata". E' la
 * stessa ragione per cui il bundle notturno passa da `BundleSwap` invece che
 * da una scrittura diretta.
 *
 * Qui si scrive accanto e poi si sposta. Lo spostamento e' atomico: o c'e' il
 * file vecchio, o c'e' quello nuovo, mai un mezzo file. E i byte si forzano
 * su disco PRIMA di spostare, altrimenti lo spostamento puo' arrivare a
 * destinazione mentre il contenuto e' ancora in memoria, e si otterrebbe un
 * file nuovo e vuoto — che e' esattamente il danno da cui si sta scappando.
 */
internal object Durable {

    /**
     * Scrive [text] in [file], o lascia [file] esattamente com'era.
     *
     * Torna false se non c'e' riuscita: chi chiama non deve dire all'utente
     * che ha salvato.
     */
    fun write(file: File, text: String): Boolean {
        val part = File(file.parentFile, file.name + PART_SUFFIX)
        return try {
            file.parentFile?.mkdirs()
            FileOutputStream(part).use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.flush()
                // I byte sul disco, non solo consegnati al sistema.
                out.fd.sync()
            }
            Files.move(
                part.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
            true
        } catch (e: Exception) {
            // Il file di lavoro non deve restare in giro: al prossimo giro
            // darebbe fastidio e a guardarlo sembrerebbe un salvataggio buono.
            runCatching { part.delete() }
            false
        }
    }

    /** Il suffisso del file di lavoro, riconoscibile e mai confondibile col vero. */
    const val PART_SUFFIX = ".part"
}
