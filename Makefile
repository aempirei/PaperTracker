PROGRAMS = ./bin/PaperTracker-debug.apk
TARGET_DIR = ~/public_html/

.PHONY: build clean

build: $(PROGRAMS)
	cp $(PROGRAMS) $(TARGET_DIR)

clean:
	ant clean

./bin/PaperTracker-debug.apk: ./src/org/buttes/papertracker/PaperTracker.java
	ant clean
	ant debug
