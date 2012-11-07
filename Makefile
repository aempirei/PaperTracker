PROGRAMS = ./bin/preview-debug.apk
TARGET_DIR = ~/public_html/

.PHONY: build clean

build: $(PROGRAMS)
	cp $(PROGRAMS) $(TARGET_DIR)

clean:
	ant clean

./bin/preview-debug.apk: ./src/org/buttes/shitpreview/preview.java
	ant clean
	ant debug
