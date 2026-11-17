"""Convert the How We Swipe logs to the one line per swipe file that TypingBench reads.

Usage: python3 bench/convert_swipes.py path/to/swipelogs build/bench-data/swipes.tsv
"""
import glob, os, random, sys

source, target = sys.argv[1], sys.argv[2]
swipes = []
for path in sorted(glob.glob(os.path.join(source, '*.log'))):
    user = os.path.basename(path)[:-4]
    current = None
    with open(path, errors='replace') as log:
        next(log, None)
        for line in log:
            fields = line.rstrip('\n').split(' ')
            if len(fields) < 12:
                continue
            try:
                time, width, height, x, y = int(fields[1]), float(fields[2]), float(fields[3]), float(fields[5]), float(fields[6])
            except ValueError:
                continue
            event, word, error = fields[4], fields[10].lower(), fields[11]
            if event == 'touchstart':
                current = dict(user=user, word=word, error=error, width=width, height=height, points=[(x, y, time)])
            elif current is not None:
                current['points'].append((x, y, time))
                if event == 'touchend':
                    if len(current['points']) >= 3 and current['word'].isalpha() and len(current['word']) >= 2:
                        swipes.append(current)
                    current = None
# A fixed shuffle mixes the users, so the fitting lines and the held-out lines hold the same kinds of swipes.
random.seed(7)
random.shuffle(swipes)
os.makedirs(os.path.dirname(target) or '.', exist_ok=True)
with open(target, 'w') as out:
    for swipe in swipes:
        start = swipe['points'][0][2]
        points = ' '.join('%g,%g,%d' % (x, y, time - start) for x, y, time in swipe['points'])
        out.write('%s\t%s\t%s\t%g\t%g\t%s\n' % (swipe['word'], swipe['error'], swipe['user'], swipe['width'], swipe['height'], points))
print(len(swipes), 'swipes written to', target)
