JANK      ?= jank
CC        := clang
MAIN      := go-brrrr.main
BIN       := target/go-brrrr
PORT      := 8443

H2O_SRC   := vendor/h2o
H2O_BUILD := build/h2o
H2O_LIB   := $(H2O_BUILD)/libh2o-evloop.a
H3CLIENT  := $(H2O_BUILD)/h2o-httpclient

# Must match libh2o-evloop's own compile definitions or struct layouts drift.
SHIM_CFLAGS := -O2 -fPIC -std=c11 -pthread -D_GNU_SOURCE -DH2O_USE_LIBUV=0 -DQUICLY_USE_TRACER=1 \
               -I$(H2O_SRC)/include -I$(H2O_SRC)/deps/picotls/include -I$(H2O_SRC)/deps/quicly/include
SYS_LIBS    := -lssl -lcrypto -lz

JANKFLAGS := --module-path src -Inative

.PHONY: run build test clean distclean

$(H2O_SRC)/CMakeLists.txt:
	git submodule update --init $(H2O_SRC)

$(H2O_LIB) $(H3CLIENT) &: $(H2O_SRC)/CMakeLists.txt
	cmake -S $(H2O_SRC) -B $(H2O_BUILD) -G Ninja -DCMAKE_BUILD_TYPE=Release \
	  -DCMAKE_POSITION_INDEPENDENT_CODE=ON -DWITH_MRUBY=OFF -DWITH_BROTLI=OFF -DWITH_ZSTD=OFF \
	  -DWITH_DTRACE=OFF -DWITH_IO_URING=OFF -DWITH_AEGIS=OFF -DWITH_CCACHE=OFF
	ninja -C $(H2O_BUILD) libh2o-evloop h2o-httpclient

build/brrrr.o: native/brrrr.c native/brrrr.h $(H2O_LIB)
	$(CC) $(SHIM_CFLAGS) -c $< -o $@

# Static archive for AOT, shared object (shim + h2o) for the JIT.
build/libbrrrr.a: build/brrrr.o
	ar rcs $@ $^

build/libbrrrr.so: build/brrrr.o $(H2O_LIB)
	$(CC) -shared -o $@ $< $(H2O_LIB) $(SYS_LIBS) -lm -pthread

# Self-signed dev cert.
certs/cert.pem:
	mkdir -p certs
	openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -nodes -days 365 \
	  -subj /CN=localhost -addext subjectAltName=DNS:localhost,IP:127.0.0.1 \
	  -keyout certs/key.pem -out certs/cert.pem 2>/dev/null

# JIT.
run: build/libbrrrr.so certs/cert.pem
	$(JANK) $(JANKFLAGS) -Lbuild -lbrrrr run-main $(MAIN)

# AOT: native binary at $(BIN); links shim + h2o statically, OpenSSL/zlib dynamically.
build: $(BIN)
SOURCES := $(shell find src -name '*.jank')
$(BIN): $(SOURCES) build/libbrrrr.a $(H2O_LIB)
	$(JANK) $(JANKFLAGS) -O2 --name go-brrrr \
	  -l $(CURDIR)/build/libbrrrr.a -l $(CURDIR)/$(H2O_LIB) $(SYS_LIBS) compile $(MAIN)

# Start the AOT server, check GET / is "23" text/plain over h1, h2 and h3.
test: $(BIN) $(H3CLIENT) certs/cert.pem
	@./scripts/smoke.sh $(BIN) $(H3CLIENT) $(PORT)

clean:
	rm -rf target build/brrrr.o build/libbrrrr.*

distclean:
	rm -rf target build certs
