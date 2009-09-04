package com.reddit.programming.mario;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import ch.idsia.ai.agents.Agent;
import ch.idsia.mario.engine.GlobalOptions;

public class ThreadedBestFirstAgent extends HeuristicSearchingAgent implements Agent {
	public static final int simultaneousSearchers = Runtime.getRuntime().availableProcessors();
	private ExecutorService searchPool = Executors.newFixedThreadPool(simultaneousSearchers);
	protected StateSearcher[] searchers = new StateSearcher[simultaneousSearchers];
	
	public ThreadedBestFirstAgent(String name) {
		super(name);
	}
	
	public ThreadedBestFirstAgent() {
		super("ThreadedBestFirstAgent");
	}

	protected final PrioQ getInitialPriorityQueue(MarioState initialState, WorldState ws) {
		bestfound = null;
		PrioQ pq = new PrioQ(Tunables.MaxBreadth);
		initialState.ws = ws;
		initialState.g = 0;
		initialState.dead = false;
		initialState.pred = null;
		// add initial set
		for(int a=0;a<16;a++) {
			if(useless_action(a, initialState))
				continue;
			MarioState ms = initialState.next(a, ws);
			ms.root_action = a;
			ms.cost = 1 + cost(ms, initialState);
			pq.offer(ms);
			if(verbose2)
				System.out.printf("BestFirst: root action %d initial cost=%f\n", a, ms.cost);
		}
		return pq;
	}
	
	protected final void initializeSearchers(MarioState initialState, WorldState ws, Object notificationObject) {
		PrioQ pq = getInitialPriorityQueue(initialState, ws);
		PrioQ[] pqs = new PrioQ[searchers.length];
		//System.out.println("creating searchers");
		for (int i = 0; i < pqs.length; i++) pqs[i] = new PrioQ(Tunables.MaxBreadth);
		int i = 0;
		while (!pq.isEmpty())
			pqs[i++%pqs.length].offer(pq.poll());
		
		for (i = 0; i < searchers.length; i++){
			searchers[i] = new StateSearcher(initialState, ws, pqs[i], i, notificationObject);
			searchPool.execute(searchers[i]);
		}
	}
	
	protected MarioState bestfound;
	protected final MarioState getBestFound() {
		for (StateSearcher searcher: searchers) {
			bestfound = marioMin(searcher.bestfound, bestfound);

			if (verbose1)
				System.out.printf("searcher_(%d): best root_action=%d cost=%f lookahead=%f\n",
						searcher.id, bestfound.root_action, bestfound.cost, bestfound.g);
		}
		return bestfound;
	}
	
	protected final void stopSearchers() {
		for (StateSearcher searcher: searchers)
			searcher.stop();
		for (StateSearcher searcher: searchers)
			while(!searcher.isStopped){}
	}
	
	protected int searchForAction(MarioState initialState, WorldState ws) {
		Object notificationObject = new Object();
		initializeSearchers(initialState, ws, notificationObject);
		try {
			synchronized(notificationObject){
				notificationObject.wait(25);
			}
		} catch (InterruptedException e) {throw new RuntimeException("Interrupted from sleep searching for the best action");}
		stopSearchers();
			
		// return best so far
		return getBestFound().root_action;
	}
	
	private class StateSearcher implements Runnable {
		private final PrioQ pq;
		private final MarioState initialState;
//		private final WorldState ws;
		final int id;
		private boolean shouldStop = false;
		public  volatile boolean isStopped = false;
		MarioState bestfound;
		private int DrawIndex = 0;
		private Object notificationObject;
		
		public StateSearcher(MarioState initialState, WorldState ws, PrioQ pq, int id, Object notificationObject) {
			this.pq = pq; //this.ws = ws; 
			this.initialState = initialState; this.bestfound = null;
			this.id = id; DrawIndex = id;
			this.notificationObject = notificationObject;
		}

		public void stop() {
			this.shouldStop = true;
		}
		
		public void run() {
			doRun();
			isStopped = true;
		}
		
		private void doRun() {
			int n = 0;
			bestfound = pq.peek();
			while((!shouldStop) && (!pq.isEmpty())) {
//				if(pq.size() > maxBreadth)
//					pq = prune_pq();
			
				MarioState next = pq.poll();

				// next.cost can be infinite, and still at the head of the queue,
				// if the node got marked dead
				if(next.cost == Float.POSITIVE_INFINITY) continue;

				bestfound = ThreadedBestFirstAgent.marioMin(next,bestfound);
				for(int a=0;a<16;a++) {
					if(HeuristicSearchingAgent.useless_action(a, next))
						continue;
					MarioState ms = next.next(a, next.ws);
					
					if (DrawIndex >= 400)
					{
						DrawIndex = 0;
					}
					
					if(ms.dead) continue;
					ms.pred = next;

					if(ms.dead)
						continue;

					float h = cost(ms, initialState);
					ms.g = next.g + Tunables.GIncrement;
					ms.cost = ms.g + h + ((a&MarioState.ACT_JUMP)>0?Tunables.FeetOnTheGroundBonus:0);
					n++;
					if(h <= 0) {
						if(ThreadedBestFirstAgent.verbose1) {
							System.out.printf("BestFirst: searched %d iterations; best a=%d cost=%f lookahead=%f\n", 
									n, ms.root_action, ms.cost, ms.g);
						}
						if(ThreadedBestFirstAgent.verbose2) {
							MarioState s;
							for(s = ms;s != initialState;s = s.pred) {
								System.out.printf("state %d: ", (int)s.g);
								s.print();
							}
						}
						bestfound = ms;
						synchronized(notificationObject) {notificationObject.notify();}
						return;
					}
					pq.offer(ms);
				}
			}
		}

	}

}
