# Regole R8 dell'app.
#
# Sono poche di proposito. Il 15/09/2026 ho verificato sul `mapping.txt` di una
# build di release vera cosa R8 fa davvero, invece di aggiungere keep a
# scaramanzia:
#
#   - i NUMERI DI RIGA ci sono (`... at(int,int,int,long):102:102`), quindi gli
#     stack trace che Crashlytics deoffusca col mapping puntano alla riga
#     giusta. Non serve `-keepattributes SourceFile,LineNumberTable`;
#   - non c'e' riflessione su classi nostre. Le uniche `::class.java` sono
#     `getSystemService` e gli `Intent`, che R8 segue da se'; i receiver, i
#     service, l'Activity e il ContentProvider stanno nel manifest, quindi
#     sono radici;
#   - il JSON e' navigato a mano con org.json: nessuna classe istanziata per
#     nome, niente da tenere;
#   - MapLibre, Compose, coroutine, DataStore, Glance e Crashlytics portano le
#     proprie regole come consumer rules.
#
# Il lettore mmap usa sun.misc.Unsafe per riflessione (rilascio della mappa su
# JVM desktop); su Android il ramo fallisce in modo pulito e non serve tenerlo.
#
# Il presidio vero non e' questo file: e' che la CI compili anche
# `:app:assembleRelease` a ogni push (.github/workflows/build-app.yml). Fino a
# settembre 2026 nessuna Action toccava l'app, quindi R8 girava solo quando
# qualcuno faceva una release a mano -- cioe' tre volte in tutto.
