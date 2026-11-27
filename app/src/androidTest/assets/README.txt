synthetic.mp4: generated specifically for Vidora tests: solid blue frames + 440 Hz sine,
2 seconds, H.264 Baseline/AAC, 160x90. No third-party footage or recordings.
Generation: ffmpeg -f lavfi -i color=c=0x3569c8:s=160x90:r=15:d=2 -f lavfi -i sine=frequency=440:duration=2 -c:v libx264 -pix_fmt yuv420p -profile:v baseline -c:a aac -movflags +faststart -shortest synthetic.mp4
The synthetic fixture is dedicated to the public domain under CC0-1.0.
