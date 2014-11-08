# NB that PATH environment variable should contain path to mxmlc
FLASH_CC := mxmlc
VPATH := src
SWF := audiocapture.swf

all: $(SWF)

%.swf: %.as
	$(FLASH_CC) -o $@ $<

clean:
	$(RM) $(SWF)

.PHONY: clean

