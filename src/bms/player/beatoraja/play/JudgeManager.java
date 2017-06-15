package bms.player.beatoraja.play;

import static bms.player.beatoraja.skin.SkinProperty.*;

import java.util.Arrays;

import bms.player.beatoraja.*;
import com.badlogic.gdx.utils.FloatArray;

import bms.model.*;
import bms.player.beatoraja.input.BMSPlayerInputProcessor;

/**
 * ノーツ判定管理用クラス
 * 
 * @author exch
 */
public class JudgeManager {

	// TODO HCN押し直しの発音はどうする？

	private final BMSPlayer main;
	/**
	 * LN type
	 */
	private int lntype;
	private Lane[] lanes;

	/**
	 * 現在の判定カウント内訳
	 */
	private IRScoreData score = new IRScoreData();

	/**
	 * 現在のコンボ数
	 */
	private int combo;
	/**
	 * コース時の現在のコンボ数
	 */
	private int coursecombo;
	/**
	 * コース時の最大コンボ数
	 */
	private int coursemaxcombo;
	/**
	 * ボムの表示開始時間
	 */
	private int[] judge;
	/**
	 * 現在表示中の判定
	 */
	private int[] judgenow;
	private int[] judgecombo;
	/**
	 * 判定差時間(ms , +は早押しで-は遅押し)
	 */
	private int judgefast;
	/**
	 * 処理中のLN
	 */
	private LongNote[] processing;
	/**
	 * 通過中のHCN
	 */
	private LongNote[] passing;
	/**
	 * HCN増加判定
	 */
	private boolean[] inclease = new boolean[8];
	private boolean[] next_inclease = new boolean[8];
	private int[] passingcount;

	private int[] keyassign;

	private int[] sckeyassign;
	private int[] sckey;
	private int[] offset;
	/**
	 * HCNの増減間隔(ms)
	 */
	private static final int hcnduration = 200;
	/**
	 * ノーツ判定テーブル
	 */
	private int[][] njudge;
	/**
	 * CN終端判定テーブル
	 */
	private int[][] cnendjudge;
	/**
	 * スクラッチ判定テーブル
	 */
	private int[][] sjudge;
	private int[][] scnendjudge;
	/**
	 * PMS用判定システム(空POORでコンボカット、1ノーツにつき1空POORまで)の有効/無効
	 */
	private boolean pmsjudge = false;

	private int prevtime;

	private boolean autoplay = false;

	private final JudgeAlgorithm algorithm;

	public JudgeManager(BMSPlayer main) {
		this.main = main;
		algorithm = main.getMainController().getPlayerResource().getConfig().getJudgealgorithm();
	}

	public void init(BMSModel model, PlayerResource resource) {
		prevtime = 0;
		judge = new int[20];
		judgenow = new int[((PlaySkin) main.getSkin()).getJudgeregion()];
		judgecombo = new int[((PlaySkin) main.getSkin()).getJudgeregion()];
		score = new IRScoreData(model.getMode());
		score.setNotes(model.getTotalNotes());
		score.setSha256(model.getSHA256());

		this.lntype = model.getLntype();
		lanes = model.getLanes();

		JudgeProperty rule = BMSPlayerRule.getBMSPlayerRule(model.getMode()).judge;
		pmsjudge = rule.pms;

		switch (model.getMode()) {
		case BEAT_5K:
			keyassign = new int[] { 0, 1, 2, 3, 4, -1, -1, 5, 5 };
			break;
		case BEAT_7K:
			keyassign = new int[] { 0, 1, 2, 3, 4, 5, 6, 7, 7 };
			break;
		case BEAT_10K:
			keyassign = new int[] { 0, 1, 2, 3, 4, -1, -1, 5, 5, 6, 7, 8, 9, 10, -1, -1, 11, 11 };
			break;
		case BEAT_14K:
			keyassign = new int[] { 0, 1, 2, 3, 4, 5, 6, 7, 7, 8, 9, 10, 11, 12, 13, 14, 15, 15 };
			break;
		case POPN_9K:
			keyassign = new int[] { 0, 1, 2, 3, 4, 5, 6, 7, 8 };
			break;
		default:
			keyassign = new int[] { 0, 1, 2, 3, 4, 5, 6, 7, 7 };
			break;
		}
		offset = new int[model.getMode().key];
		sckeyassign = new int[model.getMode().key];
		sckey = new int[model.getMode().scratchKey.length];
		for(int i = 0, sc = 0;i < offset.length;i++) {
			if(model.getMode().isScratchKey(i)) {
				sckeyassign[i] = sc;
				offset[i] = sc * 10;
				sc++;
			} else {
				sckeyassign[i] = -1;
				offset[i] = (i / (model.getMode().key / model.getMode().player)) * 10 + i % (model.getMode().key / model.getMode().player) + 1;	
			}
		}

		processing = new LongNote[sckeyassign.length];
		passing = new LongNote[sckeyassign.length];
		passingcount = new int[sckeyassign.length];
		inclease = new boolean[sckeyassign.length];
		next_inclease = new boolean[sckeyassign.length];

		final int judgerank = resource.getConfig().isExpandjudge() ? model.getJudgerank() * 4 : model.getJudgerank();
		int constraint = 2;
		for (CourseData.CourseDataConstraint mode : resource.getConstraint()) {
			if (mode == CourseData.CourseDataConstraint.NO_GREAT) {
				constraint = 0;
			} else if (mode == CourseData.CourseDataConstraint.NO_GOOD) {
				constraint = 1;
			}
		}
		njudge = rule.getNoteJudge(judgerank, constraint);
		cnendjudge = rule.getLongNoteEndJudge(judgerank, constraint);
		sjudge = rule.getScratchJudge(judgerank, constraint);
		scnendjudge = rule.getLongScratchEndJudge(judgerank, constraint);

		this.autoplay = resource.getAutoplay() == 1;
		
		FloatArray f = resource.getGauge();
		if (f != null) {
			setCourseCombo(resource.getCombo());
			setCourseMaxcombo(resource.getMaxcombo());
		}
	}

	public void update(final int time) {
		final BMSPlayerInputProcessor input = main.getMainController().getInputProcessor();
		final Config config = main.getMainController().getPlayerResource().getConfig();
		final long[] keytime = input.getTime();
		final boolean[] keystate = input.getKeystate();
		// 通過系の判定
		for (int key = 0; key < keyassign.length; key++) {
			final int lane = keyassign[key];
			if(lane == -1) {
				continue;
			}
			final Lane lanemodel = lanes[lane];
			lanemodel.mark(prevtime + njudge[4][0]);
			for(Note note = lanemodel.getNote();note != null && note.getTime() <= time;note = lanemodel.getNote()) {
				if (note instanceof LongNote) {
					// HCN判定
					final LongNote lnote = (LongNote) note;
					if ((lnote.getType() == LongNote.TYPE_UNDEFINED && lntype == BMSModel.LNTYPE_HELLCHARGENOTE)
							|| lnote.getType() == LongNote.TYPE_HELLCHARGENOTE) {
						if (lnote.isEnd()) {
							passing[lane] = null;
							passingcount[lane] = 0;
						} else {
							passing[lane] = lnote;								
						}
					}
				} else if (note instanceof MineNote && keystate[key]) {
					final MineNote mnote = (MineNote) note;
					// 地雷ノート判定
					main.getGauge().addValue(-mnote.getDamage());
					System.out.println("Mine Damage : " + mnote.getWav());
				}

				if (autoplay) {
					// ここにオートプレイ処理を入れる
					if (note instanceof NormalNote && note.getState() == 0) {
						main.play(note, config.getKeyvolume());
						this.update(lane, note, time, 0, 0);
					}
					if (note instanceof LongNote) {
						final LongNote ln = (LongNote) note;
						if (!ln.isEnd() && ln.getState() == 0) {
							main.play(note, config.getKeyvolume());
							if ((lntype == BMSModel.LNTYPE_LONGNOTE && ln.getType() == LongNote.TYPE_UNDEFINED)
									|| ln.getType() == LongNote.TYPE_LONGNOTE) {
								passingcount[lane] = 0;
							} else {
								this.update(lane, ln, time, 0, 0);
							}
							processing[lane] = ln.getPair();
						}
						if (ln.isEnd() && ln.getState() == 0) {
							if ((lntype != BMSModel.LNTYPE_LONGNOTE && ln.getType() == LongNote.TYPE_UNDEFINED)
									|| ln.getType() == LongNote.TYPE_CHARGENOTE
									|| ln.getType() == LongNote.TYPE_HELLCHARGENOTE) {
								this.update(lane, ln, time, 0, 0);
								main.play(processing[lane], config.getKeyvolume());
								processing[lane] = null;
							}
						}
					}
				}
			}
		}
		// HCNゲージ増減判定
		Arrays.fill(next_inclease, false);
		for (int key = 0; key < keyassign.length; key++) {
			final int lane = keyassign[key];
			if (lane != -1 && passing[lane] != null && (keystate[key] || autoplay)) {
				next_inclease[lane] = true;
			}
		}
		final boolean[] b = inclease;
		inclease = next_inclease;
		next_inclease = b;

		for (int key = 0; key < keyassign.length; key++) {
			final int rkey = keyassign[key];
			if (rkey == -1 || passing[rkey] == null) {
				continue;
			}
			if (inclease[rkey]) {
				passingcount[rkey] += (time - prevtime);
				if (passingcount[rkey] > hcnduration) {
					main.getGauge().update(1, 0.5f);
					// System.out.println("HCN : Gauge increase");
					passingcount[rkey] -= hcnduration;
				}
			} else {
				passingcount[rkey] -= (time - prevtime);
				if (passingcount[rkey] < -hcnduration) {
					main.getGauge().update(4, 0.5f);
					// System.out.println("HCN : Gauge decrease");
					passingcount[rkey] += hcnduration;
				}
			}
		}
		prevtime = time;

		for (int key = 0; key < keyassign.length; key++) {
			final int lane = keyassign[key];
			if (lane == -1) {
				continue;
			}
			final long ptime = keytime[key];
			if (ptime == 0) {
				continue;
			}
			final Lane lanemodel = lanes[lane];
			lanemodel.reset();
			final int sc = sckeyassign[lane];
			if (keystate[key]) {
				// キーが押されたときの処理
				if (processing[lane] != null) {
					// BSS終端処理
					if (((lntype != BMSModel.LNTYPE_LONGNOTE && processing[lane].getType() == LongNote.TYPE_UNDEFINED)
							|| processing[lane].getType() == LongNote.TYPE_CHARGENOTE
							|| processing[lane].getType() == LongNote.TYPE_HELLCHARGENOTE) && sc >= 0
							&& key != sckey[sc]) {
						final int[][] judge = scnendjudge;
						final int dtime = (int) (processing[lane].getTime() - ptime);						
						int j = 0;
						for (; j < judge.length && !(dtime >= judge[j][0] && dtime <= judge[j][1]); j++);

						this.update(lane, processing[lane], time, j, dtime);
						// System.out.println("BSS終端判定 - Time : " +
						// ptime + " Judge : " + j + " LN : " +
						// processing[lane].hashCode());
						main.play(processing[lane], config.getKeyvolume());
						processing[lane] = null;
						sckey[sc] = 0;
					} else {
						// ここに来るのはマルチキーアサイン以外ありえないはず
					}
				} else {
					final int[][] judge = sc >= 0 ? sjudge : njudge;
					// 対象ノーツの抽出
					lanemodel.reset();
					final Note tnote = algorithm.getNote(lanemodel, ptime, judge,
							lane, pmsjudge);
					final int j = algorithm.getJudge();

					if (tnote != null) {
						// TODO この時点で空POOR処理を分岐させるべきか
						if (tnote instanceof LongNote) {
							// ロングノート処理
							final LongNote ln = (LongNote) tnote;
							main.play(tnote, config.getKeyvolume());
							if ((lntype == BMSModel.LNTYPE_LONGNOTE && ln.getType() == LongNote.TYPE_UNDEFINED)
									|| ln.getType() == LongNote.TYPE_LONGNOTE) {
								passingcount[lane] = (int) (tnote.getTime() - ptime);
							} else {
								final int dtime = (int) (tnote.getTime() - ptime);
								this.update(lane, ln, time, j, dtime);
							}
							if (j < 4) {
								processing[lane] = ln.getPair();
								if (sc >= 0) {
									// BSS処理開始
									// System.out.println("BSS開始判定 - Time : " +
									// ptime + " Judge : " + j + " KEY : " + key
									// + " LN : " + note.hashCode());
									sckey[sc] = key;
								}
							}
						} else {
							main.play(tnote, config.getKeyvolume());
							// 通常ノート処理
							final int dtime = (int) (tnote.getTime() - ptime);
							this.update(lane, tnote, time, j, dtime);
						}
					} else {
						// 空POOR判定がないときのキー音処理
						Note n = null;
						boolean sound = false;
						
						for(Note note : lanemodel.getHiddens()) {
							if(note.getTime() >= ptime) {
								break;
							}
							n = note;
						}
						
						for(Note note : lanemodel.getNotes()) {
							if ((n == null || n.getTime() <= note.getTime()) 
									&& !(note instanceof LongNote && note.getState() != 0)) {
								n = note;
							}
							if (n != null && note.getTime() >= ptime) {
								main.play(n, config.getKeyvolume());
								sound = true;
								break;
							}							
						}
						
						if (!sound && n != null) {
							main.play(n, config.getKeyvolume());
						}
					}
				}
			} else {
				// キーが離されたときの処理
				if (processing[lane] != null) {
					final int[][] judge = sc >= 0 ? scnendjudge : cnendjudge;
					int dtime = (int) (processing[lane].getTime() - ptime);
					int j = 0;
					for (; j < judge.length && !(dtime >= judge[j][0] && dtime <= judge[j][1]); j++);
					
					if ((lntype != BMSModel.LNTYPE_LONGNOTE
							&& processing[lane].getType() == LongNote.TYPE_UNDEFINED)
							|| processing[lane].getType() == LongNote.TYPE_CHARGENOTE
							|| processing[lane].getType() == LongNote.TYPE_HELLCHARGENOTE) {
						// CN, HCN離し処理
						if (sc >= 0) {
							if (j != 4 || key != sckey[sc]) {
								break;
							}
							// System.out.println("BSS途中離し判定 - Time : "
							// + ptime + " Judge : " + j + " LN : "
							// + processing[lane]);
							sckey[sc] = 0;
						}
						if (j >= 3) {
							main.stop(processing[lane]);
						}
						this.update(lane, processing[lane], time, j, dtime);
						main.play(processing[lane], config.getKeyvolume());
						processing[lane] = null;
					} else {
						// LN離し処理
						if (Math.abs(passingcount[lane]) > Math.abs(dtime)) {
							dtime = passingcount[lane];
							for (; j < 4; j++) {
								if (passingcount[lane] >= judge[j][0] && passingcount[lane] <= judge[j][1]) {
									break;
								}
							}
						}
						if (j >= 3) {
							main.stop(processing[lane]);
						}
						this.update(lane, processing[lane].getPair(), time, j, dtime);
						main.play(processing[lane], config.getKeyvolume());
						processing[lane] = null;
					}
				}
			}
			keytime[key] = 0;
		}

		for (int lane = 0; lane < sckeyassign.length; lane++) {
			final int sc = sckeyassign[lane];
			final int[][] judge = sc >= 0 ? sjudge : njudge;

			// LN終端判定
			if (processing[lane] != null
					&& ((lntype == BMSModel.LNTYPE_LONGNOTE && processing[lane].getType() == LongNote.TYPE_UNDEFINED)
							|| processing[lane].getType() == LongNote.TYPE_LONGNOTE)
					&& processing[lane].getTime() < time) {
				int j = 0;
				for (; j < judge.length; j++) {
					if (passingcount[lane] >= judge[j][0] && passingcount[lane] <= judge[j][1]) {
						break;
					}
				}
				this.update(lane, processing[lane].getPair(), time, j, passingcount[lane]);
				main.play(processing[lane], config.getKeyvolume());
				processing[lane] = null;
			}
			// 見逃しPOOR判定
			final Lane lanemodel = lanes[lane];
			lanemodel.reset();
			for (Note note = lanemodel.getNote(); note != null && note.getTime() < time + judge[3][0]; note = lanemodel.getNote()) {
				final int jud = note.getTime() - time;
				if (note instanceof NormalNote && note.getState() == 0) {
					this.update(lane, note, time, 4, jud);
				} else if (note instanceof LongNote) {
					final LongNote ln = (LongNote) note;
					if (!ln.isEnd() && note.getState() == 0) {
						if ((lntype != BMSModel.LNTYPE_LONGNOTE && ln.getType() == LongNote.TYPE_UNDEFINED)
								|| ln.getType() == LongNote.TYPE_CHARGENOTE
								|| ln.getType() == LongNote.TYPE_HELLCHARGENOTE) {
							// System.out.println("CN start poor");
							this.update(lane, note, time, 4, jud);
							this.update(lane, ((LongNote) note).getPair(), time, 4, jud);
						}
						if (((lntype == BMSModel.LNTYPE_LONGNOTE && ln.getType() == LongNote.TYPE_UNDEFINED)
								|| ln.getType() == LongNote.TYPE_LONGNOTE) && processing[lane] != ln.getPair()) {
							// System.out.println("LN start poor");
							this.update(lane, note, time, 4, jud);
						}

					}
					if (((lntype != BMSModel.LNTYPE_LONGNOTE && ln.getType() == LongNote.TYPE_UNDEFINED)
							|| ln.getType() == LongNote.TYPE_CHARGENOTE || ln.getType() == LongNote.TYPE_HELLCHARGENOTE)
							&& ((LongNote) note).isEnd() && ((LongNote) note).getState() == 0) {
						// System.out.println("CN end poor");
						this.update(lane, ((LongNote) note), time, 4, jud);
						processing[lane] = null;
						if (sc >= 0) {
							sckey[sc] = 0;
						}
					}
				}
			}
			// LN処理タイマー
			// TODO processing値の変化のときのみ実行したい
			// TODO HCNは別タイマーにするかも
			if (processing[lane] != null || (passing[lane] != null && inclease[lane])) {
				if (main.getTimer()[TIMER_HOLD_1P_SCRATCH + offset[lane]] == Long.MIN_VALUE) {
					main.getTimer()[TIMER_HOLD_1P_SCRATCH + offset[lane]] = main.getNowTime();
				}
			} else {
				main.getTimer()[TIMER_HOLD_1P_SCRATCH + offset[lane]] = Long.MIN_VALUE;
			}
		}
	}

	private final int[] JUDGE_TIMER = { TIMER_JUDGE_1P, TIMER_JUDGE_2P, TIMER_JUDGE_3P };

	private void update(int lane, Note n, int time, int judge, int fast) {
		if (judge < 5) {
			n.setState(judge + 1);
		}
		n.setPlayTime(fast);
		score.addJudgeCount(judge, fast >= 0, 1);
		
		judgefast = fast;
		if (judge < 3) {
			combo++;
			score.setCombo(Math.max(score.getCombo(), combo));
			coursecombo++;
			coursemaxcombo = coursemaxcombo > coursecombo ? coursemaxcombo : coursecombo;
		} else if ((judge >= 3 && judge < 5) || (pmsjudge && judge >= 3)) {
			combo = 0;
			coursecombo = 0;
		}

		this.judge[offset[lane]] = judge == 0 ? 1 : judge * 2 + (fast > 0 ? 0 : 1);
		if (judge < 2) {
			main.getTimer()[TIMER_BOMB_1P_SCRATCH + offset[lane]] = main.getNowTime();
		}

		final int lanelength = sckeyassign.length;
		if (judgenow.length > 0) {
			main.getTimer()[JUDGE_TIMER[lane / (lanelength / judgenow.length)]] = main.getNowTime();
			judgenow[lane / (lanelength / judgenow.length)] = judge + 1;
			judgecombo[lane / (lanelength / judgenow.length)] = main.getJudgeManager().getCourseCombo();
		}
		main.update(lane, judge, time, fast);
	}

	public int getRecentJudgeTiming() {
		return judgefast;
	}

	public LongNote[] getProcessingLongNotes() {
		return processing;
	}

	public LongNote[] getPassingLongNotes() {
		return passing;
	}

	public boolean[] getHellChargeJudges() {
		return inclease;
	}

	/**
	 * 現在の1曲内のコンボ数を取得する
	 * 
	 * @return 現在のコンボ数
	 */
	public int getCombo() {
		return combo;
	}

	/**
	 * 現在のコース内のコンボ数を取得する
	 * 
	 * @return 現在のコンボ数
	 */
	public int getCourseCombo() {
		return coursecombo;
	}

	public void setCourseCombo(int combo) {
		this.coursecombo = combo;
	}

	public int getCourseMaxcombo() {
		return coursemaxcombo;
	}

	public void setCourseMaxcombo(int combo) {
		this.coursemaxcombo = combo;
	}

	public int[][] getJudgeTimeRegion() {
		return njudge;
	}

	public int[][] getScratchJudgeTimeRegion() {
		return sjudge;
	}

	public IRScoreData getScoreData() {
		return score;
	}

	/**
	 * 指定の判定のカウント数を返す
	 *
	 * @param judge
	 *            0:PG, 1:GR, 2:GD, 3:BD, 4:PR, 5:MS
	 * @return 判定のカウント数
	 */
	public int getJudgeCount(int judge) {
		return score.getJudgeCount(judge);
	}

	/**
	 * 指定の判定のカウント数を返す
	 *
	 * @param judge
	 *            0:PG, 1:GR, 2:GD, 3:BD, 4:PR, 5:MS
	 * @param fast
	 *            true:FAST, flase:SLOW
	 * @return 判定のカウント数
	 */
	public int getJudgeCount(int judge, boolean fast) {
		return score.getJudgeCount(judge, fast);
	}

	public int[] getJudge() {
		return judge;
	}

	public int[] getNowJudge() {
		return judgenow;
	}

	public int[] getNowCombo() {
		return judgecombo;
	}
}
