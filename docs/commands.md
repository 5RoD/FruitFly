# Commands

Administration commands require operator level 2. Voice commands affect only the
issuing player's consent/library. Start with one creature: this prototype shares
one real neural circuit between the players, not one brain per player.

```text
/flybrain help
/flybrain spawn
/flybrain list
/flybrain watch
/flybrain debug
/flybrain export
/flybrain brain status
/flybrain brain restart
/flybrain creature reset
/flybrain scoreboard on
/flybrain scoreboard off
/flybrain scoreboard overview
/flybrain scoreboard sensors
/flybrain scoreboard neurons
/flybrain scoreboard motion
/flybrain scoreboard performance
/flybrain scoreboard behavior

/flybrain scene give
/flybrain scene show
/flybrain scene hide
/flybrain scene list
/flybrain scene select <id>
/flybrain scene trigger
/flybrain scene reset
/flybrain scene stop
/flybrain scene remove

/flybrain voice start
/flybrain voice start confirm
/flybrain voice stop
/flybrain voice status
/flybrain voice list
/flybrain voice delete <id>
/flybrain voice clear
/flybrain voice share on
/flybrain voice share off
/flybrain voice receive on
/flybrain voice receive off
```

For a recording scene: spawn/watch a fly, give and place a marker, leave its
eight-block radius for two seconds, then walk back in. Markers bind an existing
nearby fly; they do not spawn one. The encounter is one-shot until reset.
Use `scene show` for your personal editing outlines. Use `scene stop` to cancel.
Outside triggered scenes the creature primarily stalks and retaliates, rather
than attacking constantly. Shelter destruction is limited to triggered hunts.

Voice capture starts disabled on each connection. `start` shows a confirmation;
only `start confirm` enables capture. Proximity speech only, not groups. Clips are
1-6 seconds with a 60-second speaker cap. `stop` stops new capture but does not
delete old clips. Share/receive permissions are independent. Deletion removes
managed copies and reconciles offline clients on reconnect; exported copies
cannot be recalled. Do not put runtime clips or debug exports into Git.
