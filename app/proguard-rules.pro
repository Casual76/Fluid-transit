# Regole R8 dell'app. Il lettore mmap usa sun.misc.Unsafe per riflessione
# (rilascio della mappa su JVM desktop); su Android il ramo fallisce in modo
# pulito e non serve tenerlo.
