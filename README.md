# go-brrrr

A [jank](https://jank-lang.org) program that exits with `(+ 20 3)`.

## Install jank (Ubuntu 24.04+)

```bash
curl -s https://ppa.jank-lang.org/KEY.gpg | gpg --dearmor | sudo tee /etc/apt/trusted.gpg.d/jank.gpg >/dev/null
echo "deb [signed-by=/etc/apt/trusted.gpg.d/jank.gpg] https://ppa.jank-lang.org $(lsb_release -cs) main" \
  | sudo tee /etc/apt/sources.list.d/jank.list >/dev/null
sudo apt update && sudo apt install -y jank
```

## Use

```bash
make run    # JIT, exits 23
make build  # AOT, writes target/go-brrrr
make test   # checks both exit 23
```

jank ignores `-main`'s return value (the generated `main` always returns 0),
so the exit code goes through C++ interop: `(cpp/std.exit (+ 20 3))`.
