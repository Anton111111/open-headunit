"""Reads the eight bit frame marker from raw gray frames and checks the sequence."""
import sys
raw, W, H, PX, PY, B, G = sys.argv[1], *map(int, sys.argv[2:8])
data = open(raw, 'rb').read(); fs = W * H; n = len(data) // fs
bad = []; minon, maxoff = 255, 0
for i in range(n):
    fr = data[i*fs:(i+1)*fs]; v = 0
    for b in range(8):
        x = PX + (b % 4) * G + B // 2; y = PY + (b // 4) * G + B // 2
        # promedio de 5x5 en el centro del bloque
        s = sum(fr[(y+dy)*W + x+dx] for dy in range(-2, 3) for dx in range(-2, 3)) / 25
        on = s > 128
        if on: minon = min(minon, s)
        else: maxoff = max(maxoff, s)
        v |= on << b
    if v != i: bad.append((i, v))
print(f"{n} cuadros, errores={len(bad)} {bad[:5]}, luma min encendido={minon:.0f}, max apagado={maxoff:.0f}")
