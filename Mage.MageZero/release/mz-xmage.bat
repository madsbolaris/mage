@echo off
java -Xms2g -Xmx24g -XX:+UseZGC --add-opens=java.base/java.lang=ALL-UNNAMED -jar lib/mage-magezero-1.4.58.jar %*