# tmp dir is separate from asset dir

asset dir is <test>/         : writeBoth
tmp dir is <test>.tmp/       : writeBoth.tmp
asset graph.txt   in asset dir: true
scratch.txt       in tmp dir  : true
scratch.txt       in asset dir: false
asset graph.txt   committed   : true
scratch.txt       committed   : false
PASS: tmp files stay out of the committed snapshot dir
