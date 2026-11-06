The two sine fixtures are synthetic 2-second, 440 Hz tones, created for Dontree tests.
They contain no recorded speech or music.

Generation:
ffmpeg -f lavfi -i sine=frequency=440:sample_rate=44100:duration=2 -ac 2 -c:a libmp3lame -b:a 64k sine.mp3
ffmpeg -f lavfi -i sine=frequency=440:sample_rate=48000:duration=2 -ac 2 -c:a libvorbis -q:a 2 sine.ogg
