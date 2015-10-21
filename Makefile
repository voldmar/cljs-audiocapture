# NB that PATH environment variable should contain path to mxmlc
FLASH_CC := mxmlc
VPATH := src
RESOURCES := resources
SWF := audiocapture.swf

all: $(SWF)

%.swf: %.as
	$(FLASH_CC) -o $@ $<
	mv $@ resources

clean:
	$(RM) $(SWF)

.PHONY: clean

