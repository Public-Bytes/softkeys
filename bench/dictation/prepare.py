#!/usr/bin/env python3
"""Builds the recording lists for DictationBench from an unpacked LibriSpeech split.

Usage: python3 -I prepare.py LIBRISPEECH_SPLIT_DIR OUTPUT_DIR [EVERY]

The script takes every EVERY-th utterance (default 13) in sorted order, converts each
FLAC file to 16 kHz mono 16-bit WAV with ffmpeg, and writes list.tsv. It also writes
latency.tsv with the utterances closest to 5, 10, and 15 seconds long, and long.tsv with
one recording of about two minutes made from consecutive utterances of one chapter.
"""
import os
import subprocess
import sys
import wave


def main():
    source, output = sys.argv[1], sys.argv[2]
    every = int(sys.argv[3]) if len(sys.argv) > 3 else 13
    os.makedirs(output, exist_ok=True)
    utterances = []
    for root, _, files in os.walk(source):
        for name in files:
            if name.endswith(".trans.txt"):
                with open(os.path.join(root, name), encoding="utf-8") as handle:
                    for line in handle:
                        key, _, text = line.strip().partition(" ")
                        utterances.append((key, os.path.join(root, key + ".flac"), text))
    utterances.sort()
    every_utterance = []
    for key, flac, text in utterances:
        target = os.path.join(output, key + ".wav")
        every_utterance.append((key, flac, target, text))
    chosen = every_utterance[::every]
    durations = {}
    for key, flac, target, text in every_utterance:
        convert(flac, target)
        with wave.open(target) as w:
            durations[key] = w.getnframes() / w.getframerate()
    with open(os.path.join(output, "list.tsv"), "w", encoding="utf-8") as handle:
        for key, _, target, text in chosen:
            handle.write(f"{target}\t{text}\n")
    with open(os.path.join(output, "latency.tsv"), "w", encoding="utf-8") as handle:
        for seconds in (5, 10, 15):
            key, _, target, text = min(every_utterance, key=lambda u: abs(durations[u[0]] - seconds))
            handle.write(f"{target}\t{text}\n")
    write_long(every_utterance, durations, output)
    total = sum(durations[u[0]] for u in chosen)
    print(f"{len(chosen)} utterances, {total / 60:.1f} minutes of audio")


def write_long(utterances, durations, output, seconds=120, gap=0.6):
    """Joins consecutive utterances of one chapter, with short pauses, into one long recording."""
    chapters = {}
    for key, _, target, text in utterances:
        chapters.setdefault(key.rsplit("-", 1)[0], []).append((target, text))
    for chapter, items in sorted(chapters.items()):
        if sum(durations[os.path.basename(t)[:-4]] for t, _ in items) < seconds:
            continue
        frames, words, length = [], [], 0.0
        for target, text in items:
            with wave.open(target) as w:
                frames.append(w.readframes(w.getnframes()))
            frames.append(b"\0\0" * int(16000 * gap))
            words.append(text)
            length += durations[os.path.basename(target)[:-4]] + gap
            if length >= seconds:
                break
        path = os.path.join(output, f"long-{chapter}.wav")
        with wave.open(path, "wb") as w:
            w.setnchannels(1)
            w.setsampwidth(2)
            w.setframerate(16000)
            w.writeframes(b"".join(frames))
        with open(os.path.join(output, "long.tsv"), "w", encoding="utf-8") as handle:
            handle.write(f"{path}\t{' '.join(words)}\n")
        return


def convert(flac, target):
    if os.path.exists(target):
        return
    subprocess.run(["ffmpeg", "-nostdin", "-loglevel", "error", "-i", flac, "-ar", "16000", "-ac", "1",
                    "-c:a", "pcm_s16le", target], check=True)


if __name__ == "__main__":
    main()
