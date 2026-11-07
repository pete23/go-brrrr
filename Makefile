JANK      ?= jank
MAIN      := go-brrrr.main
BIN       := target/go-brrrr
JANKFLAGS := --module-path src

.PHONY: run build test clean

# JIT: load and execute -main.
run:
	$(JANK) $(JANKFLAGS) run-main $(MAIN)

# AOT: static native binary at $(BIN).
build:
	$(JANK) $(JANKFLAGS) -O2 --name go-brrrr compile $(MAIN)

# Expect exit code 23 from both JIT and AOT.
test: build
	@$(JANK) $(JANKFLAGS) run-main $(MAIN); s=$$?; [ $$s -eq 23 ] || { echo "jit: got $$s, want 23"; exit 1; }
	@./$(BIN); s=$$?; [ $$s -eq 23 ] || { echo "aot: got $$s, want 23"; exit 1; }
	@echo "ok: jit and aot both exit 23"

clean:
	rm -rf target
