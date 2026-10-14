/*
 * Copyright (C) 2012, Christian Halstrick <christian.halstrick@sap.com> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.internal.storage.file;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.internal.storage.pack.StoredObjectRepresentation;
import org.eclipse.jgit.junit.TestRepository;
import org.eclipse.jgit.junit.TestRepository.BranchBuilder;
import org.eclipse.jgit.lib.ConfigConstants;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.GcConfig;
import org.eclipse.jgit.lib.GcConfig.PackRefsMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevBlob;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.eclipse.jgit.storage.pack.PackConfig;
import org.junit.Test;
import org.junit.experimental.theories.DataPoints;
import org.junit.experimental.theories.Theories;
import org.junit.experimental.theories.Theory;
import org.junit.runner.RunWith;

@RunWith(Theories.class)
public class GcBasicPackingTest extends GcTestCase {
	@DataPoints
	public static boolean[] aggressiveValues = { true, false };

	@Theory
	public void repackEmptyRepo_noPackCreated(boolean aggressive)
			throws IOException {
		configureGc(gc, aggressive);
		gc.repack();
		assertEquals(0, repo.getObjectDatabase().getPacks().size());
	}

	@Theory
	public void testPackRepoWithNoRefs(boolean aggressive) throws Exception {
		tr.commit().add("A", "A").add("B", "B").create();
		stats = gc.getStatistics();
		assertEquals(4, stats.numberOfLooseObjects);
		assertEquals(0, stats.numberOfPackedObjects);
		configureGc(gc, aggressive);
		gc.gc().get();
		stats = gc.getStatistics();
		assertEquals(4, stats.numberOfLooseObjects);
		assertEquals(0, stats.numberOfPackedObjects);
		assertEquals(0, stats.numberOfPackFiles);
		assertEquals(0, stats.numberOfBitmaps);
	}

	@Theory
	public void testPack2Commits(boolean aggressive) throws Exception {
		BranchBuilder bb = tr.branch("refs/heads/master");
		bb.commit().add("A", "A").add("B", "B").create();
		bb.commit().add("A", "A2").add("B", "B2").create();

		stats = gc.getStatistics();
		assertEquals(8, stats.numberOfLooseObjects);
		assertEquals(0, stats.numberOfPackedObjects);
		configureGc(gc, aggressive);
		gc.gc().get();
		stats = gc.getStatistics();
		assertEquals(0, stats.numberOfLooseObjects);
		assertEquals(8, stats.numberOfPackedObjects);
		assertEquals(1, stats.numberOfPackFiles);
		assertEquals(2, stats.numberOfBitmaps);
	}

	@Theory
	public void testPack2Commits_noPackFolder(boolean aggressive) throws Exception {
		File packDir = repo.getObjectDatabase().getPackDirectory();
		assertTrue(packDir.delete());

		BranchBuilder bb = tr.branch("refs/heads/master");
		bb.commit().add("A", "A").add("B", "B").create();
		bb.commit().add("A", "A2").add("B", "B2").create();

		stats = gc.getStatistics();
		assertEquals(8, stats.numberOfLooseObjects);
		assertEquals(0, stats.numberOfPackedObjects);
		configureGc(gc, aggressive);
		gc.gc().get();
		stats = gc.getStatistics();
		assertEquals(0, stats.numberOfLooseObjects);
		assertEquals(8, stats.numberOfPackedObjects);
		assertEquals(1, stats.numberOfPackFiles);
		assertEquals(2, stats.numberOfBitmaps);

		assertTrue(packDir.exists());
	}

	@Theory
	public void testPackAllObjectsInOnePack(boolean aggressive)
			throws Exception {
		tr.branch("refs/heads/master").commit().add("A", "A").add("B", "B")
				.create();
		stats = gc.getStatistics();
		assertEquals(4, stats.numberOfLooseObjects);
		assertEquals(0, stats.numberOfPackedObjects);
		configureGc(gc, aggressive);
		gc.gc().get();
		stats = gc.getStatistics();
		assertEquals(0, stats.numberOfLooseObjects);
		assertEquals(4, stats.numberOfPackedObjects);
		assertEquals(1, stats.numberOfPackFiles);
		assertEquals(1, stats.numberOfBitmaps);

		// Do the gc again and check that it hasn't changed anything
		gc.gc().get();
		stats = gc.getStatistics();
		assertEquals(0, stats.numberOfLooseObjects);
		assertEquals(4, stats.numberOfPackedObjects);
		assertEquals(1, stats.numberOfPackFiles);
		assertEquals(1, stats.numberOfBitmaps);
	}

	@Theory
	public void testPackCommitsAndLooseOne(boolean aggressive)
			throws Exception {
		BranchBuilder bb = tr.branch("refs/heads/master");
		RevCommit first = bb.commit().add("A", "A").add("B", "B").create();
		bb.commit().add("A", "A2").add("B", "B2").create();
		tr.update("refs/heads/master", first);

		stats = gc.getStatistics();
		assertEquals(8, stats.numberOfLooseObjects);
		assertEquals(0, stats.numberOfPackedObjects);
		configureGc(gc, aggressive);
		gc.gc().get();
		stats = gc.getStatistics();
		assertEquals(0, stats.numberOfLooseObjects);
		assertEquals(8, stats.numberOfPackedObjects);
		assertEquals(2, stats.numberOfPackFiles);
		assertEquals(1, stats.numberOfBitmaps);
	}

	@Theory
	public void testNotPackTwice(boolean aggressive) throws Exception {
		BranchBuilder bb = tr.branch("refs/heads/master");
		RevCommit first = bb.commit().message("M").add("M", "M").create();
		bb.commit().message("B").add("B", "Q").create();
		bb.commit().message("A").add("A", "A").create();
		RevCommit second = tr.commit().parent(first).message("R").add("R", "Q")
				.create();
		tr.update("refs/tags/t1", second);

		Collection<Pack> oldPacks = tr.getRepository().getObjectDatabase()
				.getPacks();
		assertEquals(0, oldPacks.size());
		stats = gc.getStatistics();
		assertEquals(11, stats.numberOfLooseObjects);
		assertEquals(0, stats.numberOfPackedObjects);

		gc.setExpireAgeMillis(0);
		fsTick();
		configureGc(gc, aggressive);
		gc.gc().get();
		stats = gc.getStatistics();
		assertEquals(0, stats.numberOfLooseObjects);

		List<Pack> packs = new ArrayList<>(
				repo.getObjectDatabase().getPacks());
		assertEquals(11, packs.get(0).getObjectCount());
	}

	@Test
	public void testDonePruneTooYoungPacks() throws Exception {
		BranchBuilder bb = tr.branch("refs/heads/master");
		bb.commit().message("M").add("M", "M").create();

		String tempRef = "refs/heads/soon-to-be-unreferenced";
		BranchBuilder bb2 = tr.branch(tempRef);
		bb2.commit().message("M").add("M", "M").create();

		gc.setExpireAgeMillis(0);
		gc.gc().get();
		stats = gc.getStatistics();
		assertEquals(0, stats.numberOfLooseObjects);
		assertEquals(4, stats.numberOfPackedObjects);
		assertEquals(1, stats.numberOfPackFiles);
		File oldPackfile = tr.getRepository().getObjectDatabase().getPacks()
				.iterator().next().getPackFile();

		fsTick();

		// delete the temp ref, orphaning its commit
		RefUpdate update = tr.getRepository().getRefDatabase().newUpdate(tempRef, false);
		update.setForceUpdate(true);
		update.delete();

		bb.commit().message("B").add("B", "Q").create();

		// The old packfile is too young to be deleted. We should end up with
		// two pack files
		gc.setExpire(Instant.ofEpochMilli(oldPackfile.lastModified() - 1));
		gc.gc().get();
		stats = gc.getStatistics();
		assertEquals(0, stats.numberOfLooseObjects);
		// if objects exist in multiple packFiles then they are counted multiple
		// times
		assertEquals(10, stats.numberOfPackedObjects);
		assertEquals(2, stats.numberOfPackFiles);

		// repack again but now without a grace period for loose objects. Since
		// we don't have loose objects anymore this shouldn't change anything
		gc.setExpireAgeMillis(0);
		gc.gc().get();
		stats = gc.getStatistics();
		assertEquals(0, stats.numberOfLooseObjects);
		// if objects exist in multiple packFiles then they are counted multiple
		// times
		assertEquals(10, stats.numberOfPackedObjects);
		assertEquals(2, stats.numberOfPackFiles);

		// repack again but now without a grace period for packfiles. We should
		// end up with one packfile
		gc.setPackExpireAgeMillis(0);

		// we want to keep newly-loosened objects though
		gc.setExpireAgeMillis(-1);

		gc.gc().get();
		stats = gc.getStatistics();
		assertEquals(1, stats.numberOfLooseObjects);
		// if objects exist in multiple packFiles then they are counted multiple
		// times
		assertEquals(6, stats.numberOfPackedObjects);
		assertEquals(1, stats.numberOfPackFiles);
	}

	@Test
	public void testImmediatePruning() throws Exception {
		BranchBuilder bb = tr.branch("refs/heads/master");
		bb.commit().message("M").add("M", "M").create();

		String tempRef = "refs/heads/soon-to-be-unreferenced";
		BranchBuilder bb2 = tr.branch(tempRef);
		bb2.commit().message("M").add("M", "M").create();

		gc.setExpireAgeMillis(0);
		gc.gc().get();
		stats = gc.getStatistics();

		fsTick();

		// delete the temp ref, orphaning its commit
		RefUpdate update = tr.getRepository().getRefDatabase().newUpdate(tempRef, false);
		update.setForceUpdate(true);
		update.delete();

		bb.commit().message("B").add("B", "Q").create();

		// We want to immediately prune deleted objects
		FileBasedConfig config = repo.getConfig();
		config.setString(ConfigConstants.CONFIG_GC_SECTION, null,
			ConfigConstants.CONFIG_KEY_PRUNEEXPIRE, "now");
		config.save();

		//And we don't want to keep packs full of dead objects
		gc.setPackExpireAgeMillis(0);

		gc.gc().get();
		stats = gc.getStatistics();
		assertEquals(0, stats.numberOfLooseObjects);
		assertEquals(6, stats.numberOfPackedObjects);
		assertEquals(1, stats.numberOfPackFiles);
	}

	@Test
	public void testPreserveAndPruneOldPacks() throws Exception {
		testPreserveOldPacks();
		configureGc(gc, false).setPrunePreserved(true);
		gc.gc().get();

		assertFalse(repo.getObjectDatabase().getPreservedDirectory().exists());
	}

	private void testPreserveOldPacks() throws Exception {
		BranchBuilder bb = tr.branch("refs/heads/master");
		bb.commit().message("P").add("P", "P").create();

		// pack loose object into packfile
		gc.setExpireAgeMillis(0);
		gc.gc().get();
		PackFile oldPackfile = tr.getRepository().getObjectDatabase().getPacks()
				.iterator().next().getPackFile();
		assertTrue(oldPackfile.exists());

		fsTick();
		bb.commit().message("B").add("B", "Q").create();

		// repack again but now without a grace period for packfiles. We should
		// end up with a new packfile and the old one should be placed in the
		// preserved directory
		gc.setPackExpireAgeMillis(0);
		configureGc(gc, false).setPreserveOldPacks(true);
		gc.gc().get();

		File preservedPackFile = oldPackfile.createPreservedForDirectory(
				repo.getObjectDatabase().getPreservedDirectory());
		assertTrue(preservedPackFile.exists());
	}

	@Test
	public void testPruneAndRestoreOldPacks() throws Exception {
		String tempRef = "refs/heads/soon-to-be-unreferenced";
		BranchBuilder bb = tr.branch(tempRef);
		bb.commit().add("A", "A").add("B", "B").create();

		// Verify setup conditions
		stats = gc.getStatistics();
		assertEquals(4, stats.numberOfLooseObjects);
		assertEquals(0, stats.numberOfPackedObjects);

		// Force all referenced objects into packs (to avoid having loose objects)
		configureGc(gc, false);
		gc.setExpireAgeMillis(0);
		gc.setPackExpireAgeMillis(0);
		gc.gc().get();
		stats = gc.getStatistics();
		assertEquals(0, stats.numberOfLooseObjects);
		assertEquals(4, stats.numberOfPackedObjects);
		assertEquals(1, stats.numberOfPackFiles);

		// Delete the temp ref, orphaning its commit
		RefUpdate update = tr.getRepository().getRefDatabase().newUpdate(tempRef, false);
		update.setForceUpdate(true);
		ObjectId objectId = update.getOldObjectId(); // remember it so we can restore it!
		RefUpdate.Result result = update.delete();
		assertEquals(RefUpdate.Result.FORCED, result);

		fsTick();

		// Repack with only orphaned commit, so packfile will be pruned
		configureGc(gc, false).setPreserveOldPacks(true);
		gc.gc().get();
		stats = gc.getStatistics();
		assertEquals(0, stats.numberOfLooseObjects);
		assertEquals(0, stats.numberOfPackedObjects);
		assertEquals(0, stats.numberOfPackFiles);

		// Restore the temp ref to the deleted commit, should restore old-packs!
		update = tr.getRepository().getRefDatabase().newUpdate(tempRef, false);
		update.setNewObjectId(objectId);
		update.setExpectedOldObjectId(null);
		result = update.update();
		assertEquals(RefUpdate.Result.NEW, result);

		stats = gc.getStatistics();
		assertEquals(4, stats.numberOfPackedObjects);
		assertEquals(1, stats.numberOfPackFiles);
	}

	private PackConfig configureGc(GC myGc, boolean aggressive) {
		PackConfig pconfig = new PackConfig(repo);
		if (aggressive) {
			pconfig.setDeltaSearchWindowSize(250);
			pconfig.setMaxDeltaDepth(250);
			pconfig.setReuseObjects(false);
		} else
			pconfig = new PackConfig(repo);
		myGc.setPackConfig(pconfig);
		return pconfig;
	}

	@Test
	public void testPackRefs() throws Exception {
		tr.branch("refs/heads/master").commit().add("A", "A").add("B", "B")
				.create();
		assertHasLooseRef(repo);

		// by default, refs should be packed
		gc.gc().get();
		assertHasNoLooseRef(repo);

		// now create a loose ref again
		tr.branch("refs/heads/foo").commit().add("C", "C").create();
		assertHasLooseRef(repo);
		// and disable packing of refs
		gc.setGcConfig(new GcConfig(PackRefsMode.FALSE));
		gc.gc().get();
		assertHasLooseRef(repo);
	}

	@Test
	public void testPackRefsWithConfig() throws Exception {
		tr.branch("refs/heads/master").commit().add("A", "A").add("B", "B")
				.create();
		assertHasLooseRef(repo);

		// disable packing of refs via config
		FileBasedConfig config = repo.getConfig();
		config.setEnum(ConfigConstants.CONFIG_GC_SECTION, null,
				ConfigConstants.CONFIG_KEY_PACK_REFS, PackRefsMode.FALSE);
		config.save();
		reloadGcFromRepoConfig();

		gc.gc().get();
		assertHasLooseRef(repo);

		// now enable packing of refs via API
		gc.setGcConfig(new GcConfig(PackRefsMode.TRUE));
		gc.gc().get();
		assertHasNoLooseRef(repo);
	}

	@Test
	public void testPackRefsWithNotBareConfig() throws Exception {
		// non-bare repo
		tr.branch("refs/heads/master").commit().add("A", "A").add("B", "B")
				.create();
		assertHasLooseRef(repo);

		// configure packing of refs only for non-bare repositories
		FileBasedConfig config = repo.getConfig();
		config.setEnum(ConfigConstants.CONFIG_GC_SECTION, null,
				ConfigConstants.CONFIG_KEY_PACK_REFS, PackRefsMode.NOTBARE);
		config.save();
		reloadGcFromRepoConfig();

		gc.gc().get();
		assertHasNoLooseRef(repo);

		// bare repo
		String repoUri = repo.getDirectory().toURI().toString();
		File dir = createUniqueTestGitDir(true);
		try (FileRepository bareRepo = (FileRepository) Git.cloneRepository()
				.setBare(true).setURI(repoUri).setDirectory(dir).call()
				.getRepository(); Git git = new Git(bareRepo)) {
			git.branchCreate().setName("refs/heads/branch-in-bare-repo")
					.setStartPoint("refs/heads/master").call();
			assertHasLooseRef(bareRepo);

			// configure packing of refs only for non-bare repositories
			FileBasedConfig bareConfig = bareRepo.getConfig();
			bareConfig.setEnum(ConfigConstants.CONFIG_GC_SECTION, null,
					ConfigConstants.CONFIG_KEY_PACK_REFS, PackRefsMode.NOTBARE);
			bareConfig.save();
			// create a new GC instance to reread the config
			gc = new GC(bareRepo);

			gc.gc().get();
			assertHasLooseRef(bareRepo);
		}
	}

	private static boolean hasLooseRef(FileRepository repository)
			throws IOException {
		return repository.getRefDatabase().getRefs().stream()
				.filter(r -> !r.isSymbolic())
				.anyMatch(r -> r.getStorage().isLoose());
	}

	private void reloadGcFromRepoConfig() {
		gc = new GC(repo);
	}

	private static void assertHasLooseRef(FileRepository repository)
			throws IOException {
		assertTrue("should have loose ref", hasLooseRef(repository));
	}

	private static void assertHasNoLooseRef(FileRepository repository)
			throws IOException {
		assertFalse("should have no loose ref", hasLooseRef(repository));
	}

	@Test
	public void testGcSearchForReuseTimeoutAcrossMultiplePacks()
			throws Exception {
		StringBuilder base = new StringBuilder(4096);
		for (int i = 0; i < 256; i++) {
			base.append("0123456789abcdef");
		}
		RevBlob b1 = tr.blob(base + "A");
		RevBlob b2 = tr.blob(base + "B");
		BranchBuilder bb = tr.branch("refs/heads/master");
		bb.commit().add("a", b1).add("b", b2).create();

		// Oldest pack P1: store b1 and b2 as PACK_WHOLE without deltas.
		PackConfig p1Config = new PackConfig(repo);
		p1Config.setDeltaCompress(false);
		p1Config.setReuseDeltas(false);
		gc.setPackConfig(p1Config);
		gc.setPackExpireAgeMillis(Long.MAX_VALUE);
		gc.setExpireAgeMillis(Long.MAX_VALUE);
		gc.gc().get();
		assertEquals(1, repo.getObjectDatabase().getPacks().size());

		fsTick();

		// Newer pack P2: store b1 and b2 with delta compression enabled.
		bb.commit().add("c", "c").create();
		PackConfig p2Config = new PackConfig(repo);
		p2Config.setDeltaCompress(true);
		p2Config.setReuseObjects(false);
		p2Config.setReuseDeltas(false);
		gc.setPackConfig(p2Config);
		gc.gc().get();
		assertEquals(2, repo.getObjectDatabase().getPacks().size());

		fsTick();

		// Third GC with expired searchForReuseTimeout and deltaCompress=false:
		// P1 (oldest) is scanned first (selecting PACK_WHOLE), then timeout
		// aborts searchForReuse before P2's PACK_DELTA representation is reached.
		PackConfig gcConfig = new PackConfig(repo);
		gcConfig.setDeltaCompress(false);
		gcConfig.setSearchForReuseTimeout(Duration.ofSeconds(-1));
		gc.setPackConfig(gcConfig);
		gc.setPackExpireAgeMillis(0);
		gc.setExpireAgeMillis(0);
		Collection<Pack> packs = gc.gc().get();
		assertEquals(1, packs.size());

		Pack resultPack = packs.iterator().next();
		try (WindowCursor curs = (WindowCursor) repo.newObjectReader()) {
			assertEquals(StoredObjectRepresentation.PACK_WHOLE,
					resultPack.representation(curs, b1).getFormat());
			assertEquals(StoredObjectRepresentation.PACK_WHOLE,
					resultPack.representation(curs, b2).getFormat());
		}
	}

	@Test
	public void testGcSearchForReuseTimeoutSkipsAlternates() throws Exception {
		FileRepository altRepo = createBareRepository();
		StringBuilder base = new StringBuilder(4096);
		for (int i = 0; i < 256; i++) {
			base.append("0123456789abcdef");
		}
		RevBlob b1;
		RevBlob b2;
		RevCommit altCommit;
		altRepo.incrementOpen();
		try (TestRepository<Repository> altTr = new TestRepository<>(altRepo)) {
			b1 = altTr.blob(base + "A");
			b2 = altTr.blob(base + "B");
			altCommit = altTr.branch("refs/heads/master").commit()
					.add("a", b1).add("b", b2).create();
		}

		GC altGc = new GC(altRepo);
		altGc.setPackExpireAgeMillis(0);
		altGc.setExpireAgeMillis(0);
		Collection<Pack> altPacks = altGc.gc().get();
		assertEquals(1, altPacks.size());
		Pack altPack = altPacks.iterator().next();
		try (WindowCursor curs = (WindowCursor) altRepo.newObjectReader()) {
			int f1 = altPack.representation(curs, b1).getFormat();
			int f2 = altPack.representation(curs, b2).getFormat();
			assertTrue("expected delta in alternate pack",
					f1 == StoredObjectRepresentation.PACK_DELTA
							|| f2 == StoredObjectRepresentation.PACK_DELTA);
		}

		File altFile = new File(repo.getObjectDatabase().getDirectory(),
				Constants.INFO_ALTERNATES);
		altFile.getParentFile().mkdirs();
		Files.write(altFile.toPath(),
				(altRepo.getObjectDatabase().getDirectory().getAbsolutePath()
						+ "\n").getBytes(StandardCharsets.UTF_8));

		RefUpdate ru = repo.updateRef("refs/heads/master");
		ru.setNewObjectId(altCommit);
		assertEquals(RefUpdate.Result.NEW, ru.forceUpdate());

		PackConfig gcConfig = new PackConfig(repo);
		gcConfig.setDeltaCompress(false);
		gcConfig.setSearchForReuseTimeout(Duration.ofSeconds(-1));
		gc.setPackConfig(gcConfig);
		gc.setPackExpireAgeMillis(0);
		gc.setExpireAgeMillis(0);
		Collection<Pack> packs = gc.gc().get();
		assertEquals(1, packs.size());

		Pack resultPack = packs.iterator().next();
		try (WindowCursor curs = (WindowCursor) repo.newObjectReader()) {
			assertEquals(StoredObjectRepresentation.PACK_WHOLE,
					resultPack.representation(curs, b1).getFormat());
			assertEquals(StoredObjectRepresentation.PACK_WHOLE,
					resultPack.representation(curs, b2).getFormat());
		}
	}
}
