#!/usr/bin/env python3
"""
mic_receiver.py — desktop counterpart of STB Control's mic streaming.

Listens on UDP 5005 for raw 16 kHz / mono / 16-bit PCM datagrams sent by the
"Mic" tab of the STB Control app and plays them live through the default
Windows speakers.

Usage:
    pip install pyaudio
    python mic_receiver.py

Optional args:
    python mic_receiver.py --port 5005

Press Ctrl+C to stop. The TV/app address is printed once the first packet
arrives so you can confirm which sender is streaming.
"""

import argparse
import socket
import sys

try:
    import pyaudio
except ImportError:
    print("pyaudio is missing — install it with:  pip install pyaudio")
    sys.exit(1)

SAMPLE_RATE = 16000
CHANNELS = 1
SAMPLE_WIDTH = 2            # 16-bit
CHUNK_BYTES = 1280          # 40 ms per packet, matches the Android app


def main() -> None:
    parser = argparse.ArgumentParser(description="Live mic stream receiver")
    parser.add_argument("--port", type=int, default=5005, help="UDP port to listen on")
    args = parser.parse_args()

    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    sock.bind(("0.0.0.0", args.port))
    sock.settimeout(0.5)

    p = pyaudio.PyAudio()
    stream = p.open(
        format=pyaudio.paInt16,
        channels=CHANNELS,
        rate=SAMPLE_RATE,
        output=True,
        frames_per_buffer=CHUNK_BYTES // SAMPLE_WIDTH,
    )

    print(f"Listening on UDP {args.port} — waiting for the app to start streaming…")
    sender_seen = False

    try:
        while True:
            try:
                data, addr = sock.recvfrom(4096)
            except socket.timeout:
                continue
            if not sender_seen:
                print(f"Receiving live mic audio from {addr[0]}           ")
                sender_seen = True
            stream.write(data)
    except KeyboardInterrupt:
        print("\nStopping.")
    finally:
        stream.stop_stream()
        stream.close()
        p.terminate()
        sock.close()


if __name__ == "__main__":
    main()
