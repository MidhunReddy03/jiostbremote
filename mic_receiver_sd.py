# mic_receiver.py — fallback player using `sounddevice` + numpy
# Use this if `pip install pyaudio` fails on your Python/Windows combo:
#     pip install sounddevice numpy
#     python mic_receiver_sd.py
import socket

import numpy as np
import sounddevice as sd

SAMPLE_RATE = 16000
CHUNK_SAMPLES = 640  # 40 ms

sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
sock.bind(("0.0.0.0", 5005))
sock.settimeout(0.5)

print("Listening on UDP 5005 (sounddevice fallback)…")
seen = False
try:
    while True:
        try:
            data, addr = sock.recvfrom(4096)
        except socket.timeout:
            continue
        if not seen:
            print(f"Receiving live mic audio from {addr[0]}")
            seen = True
        pcm = np.frombuffer(data, dtype=np.int16)
        sd.play(pcm, samplerate=SAMPLE_RATE, blocking=False)
except KeyboardInterrupt:
    print("\nStopping.")
finally:
    sock.close()
