# Verten finner CarAppService via manifestet og instansierer Session og Screen først i runtime.
# aapt genererer keep-regler for komponenter som står i manifestet, men bare for selve klassen -
# ikke for konstruktørene R8 kan se som ubrukte. Blir noe av dette strippet, feiler ikke bygget:
# appen forsvinner bare stille fra app-oversikten i bilen, eller kobler til og krasjer uten
# stacktrace i en logg du har tilgang til. Uten Play Console er det ingen crash-rapporter som
# fanger det opp, så vi holder hele bil-pakken eksplisitt.
-keep class no.synth.botometer.car.** { *; }

# Kalles av systemet, ikke fra vår egen kode.
-keep class no.synth.botometer.speed.LocationForegroundService { *; }
-keep class no.synth.botometer.BotometerApp { *; }
