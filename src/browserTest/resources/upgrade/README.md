# Upgrade fixture: m010

`m010.sqlite` is a Hohenheim control-plane database written by the code of tag `before-module-fit` at Hohenheim's
production migration level (stream `be.elevenways.hohenheim` through `010`, every framework stream whole), seeded
through the old writers by `M010FixtureSeeder.java.txt`. `m010.keys` is its field-encryption keyring (the database's
keyring marker names it) and `m010.properties` the seed's facts (ids, the API key, session secret, receipt inputs).
`HohenheimUpgradeJourneyTest` upgrades a copy under today's code.

Made on 2026-10-01 in an isolated clone, with `~/.local/bin/zenit-dev` run from inside it:

```sh
SRC=~/projects/zenit-workspace; OLD=~/projects/zenit-workspace-old
git clone --branch before-module-fit $SRC $OLD
for r in $(git -C $OLD show HEAD:workspace.json | sed -n 's/.*"name": "\(.*\)".*/\1/p'); do
  git clone --branch before-module-fit $SRC/$r $OLD/$r; done
for a in hohenheim spamservice; do git clone --branch before-module-fit $SRC/apps/$a $OLD/apps/$a; done
# $OLD/.zenit-dev.json gains "mavenRepoLocal": ".m2-repository" (its own maven-local repository)
cp M010FixtureSeeder.java.txt \
  $OLD/apps/hohenheim/src/browserTest/java/be/elevenways/hohenheim/test/migration/M010FixtureSeeder.java
cd $OLD/apps/hohenheim && zenit-dev build && zenit-dev test --browser --class M010FixtureSeeder
sqlite3 $OLD/m010.sqlite "VACUUM INTO 'm010.sqlite'"
cp $OLD/m010.sqlite.properties m010.properties
cp $OLD/apps/hohenheim/build/test-settings-root/browserTestStandalone/settings/field-encryption.keys m010.keys
```

Sites and instances are inserted at their M010 table shape (the tag's live models already carry M011's columns);
every other row, the site's update and its revision included, is written by the old live code.
