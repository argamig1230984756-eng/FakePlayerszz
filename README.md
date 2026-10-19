# SkyBlockPlus (Paper 1.21.11 + Velocity)

Build:
  cd paper    && gradle build   -> build/libs/SkyBlockPlus-Paper.jar    (Java 21)
  cd velocity && gradle build   -> build/libs/SkyBlockPlus-Velocity.jar (Java 17+)

Setup:
  1. Put the jars in each server's / the proxy's plugins folder.
  2. Set the SAME secret in paper config.yml and velocity plugins/skyblockplus/config.properties.
  3. Backend server names are taken from velocity.toml automatically.

Fake players are only visible on the server they live on: Velocity does NOT add them to the tab
list or name suggestions of players on other servers. Optional in velocity config.properties:
  show-in-server-list=true|false   (count fake players in the proxy's server-list player count)

Performance (paper config.yml -> performance:)
  idle-tick-interval   idle fake players tick fully only every N ticks (1 = off)
  view-distance / simulation-distance   chunks loaded/sent/simulated around each fake player

Paper: /fp add|remove|list|info|tp|set|rename|skin|inv|patrol|say|cmd|action|respawn|save|reload
Velocity: /vfp list, /fglist, /vfp run <server> <fp command>
