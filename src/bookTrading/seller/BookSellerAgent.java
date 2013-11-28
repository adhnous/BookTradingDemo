package bookTrading.seller;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import bookTrading.common.Proposal;

import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.core.behaviours.TickerBehaviour;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import jade.lang.acl.UnreadableException;

public class BookSellerAgent extends Agent {
	private static final long serialVersionUID = 408650196499616944L;

	/** The catalogue of books currently on sale. */
	private Map<String, PriceManager> catalogue;
	/** The GUI controlling user interaction. */
	private BookSellerGUI gui;
	
	@Override
	protected void setup() {
		// initialize the catalogue
		catalogue = new HashMap<String, PriceManager>();
		
		// start and show a new GUI
		gui = new BookSellerGUIText(this);
		gui.show();
		
		// start the two behaviour servers:
		// serve calls for price from buyer agents
		addBehaviour(new CallForOfferServer());
		// serve purchase requests from buyer agents
		addBehaviour(new PurchaseOrderServer());
	}
	@Override
	protected void takeDown() {
		// get rid of the GUI if it's there
		if(gui != null) {
			gui = null;
		}
		
		// print a goodbye message
		System.out.println("BSA " + getAID().getName() + " terminating.");
	}
	
	/**
	 * Put a new book up for sale.
	 */
	public void putForSale(String bookTitle, int initPrice, int minPrice, Date deadline) {
		addBehaviour(new PriceManager(this, bookTitle, initPrice, minPrice, deadline));
	}
	
	/**
	 * Incrementally (and linearly) accept a lower price as long as the book
	 * hasn't been sold, all the way from the initial price and up to the
	 * minimum price.
	 * 
	 * @author peter
	 *
	 */
	private class PriceManager extends TickerBehaviour {
		private static final long serialVersionUID = -5667551287935590044L;
		
		/** How often to wake up and decrease the price. */
		private static final long TICKER_INTERVAL = 60000;
		/** What to tell the user when we can't sell the book by the given deadline. */
		private static final String EXPR_MSG = "Cannot sell the book %s.";

		private String bookTitle;
		private int initPrice, currentPrice, deltaP;
		private long initTime, deadline, deltaT;
		
		public PriceManager(Agent agent, String bookTitle, int initPrice, int minPrice, Date deadline) {
			super(agent, TICKER_INTERVAL);
			
			// save the given arguments
			this.bookTitle = bookTitle;
			this.initPrice = initPrice;
			this.deadline = deadline.getTime();
			
			// work out some stuff
			this.deltaP = initPrice - minPrice;
			this.initTime = System.currentTimeMillis();
			this.deltaT = this.deadline - this.initTime;
			
			// at first the current price is the initial price
			this.currentPrice = initPrice;
		}
		
		@Override
		public void onStart() {
			// add the book to the seller agent's catalogue
			catalogue.put(bookTitle, this);
			super.onStart();
		}

		@Override
		protected void onTick() {
			long currentTime = System.currentTimeMillis();
			// if the deadline expired
			if(currentTime > deadline) {
				// the book is no longer on sale
				catalogue.remove(bookTitle);
				// notify the user
				gui.notifyUser(String.format(EXPR_MSG, bookTitle));
				// this behaviour is now useless
				stop();
			} else {
				// work out the current price
				long elapsedTime = currentTime - initTime;
				currentPrice = initPrice - deltaP * (int) (elapsedTime / deltaT);
			}
		}
		
		public int getCurrentPrice() {
			return currentPrice;
		}
		
	}
	
	/**
	 * Serve incoming Call For Proposal CA's from buyer agents.
	 * 
	 * Receives an incoming CFP.
	 * If the book is in the catalogue:
	 * 	-> Replies with a PROPOSE message containing the proposal price.
	 * Otherwise,
	 * 	-> Replies with a REFUSE message.
	 * 
	 * @author peter
	 *
	 */
	private class CallForOfferServer extends CyclicBehaviour {
		private static final long serialVersionUID = 5093118259000491987L;
		
		// we only care about CFP messages
		private MessageTemplate template =
				MessageTemplate.MatchPerformative(ACLMessage.CFP);

		@Override
		public void action() {
			ACLMessage msg = myAgent.receive(template);
			
			// block until we have a [CFP] message
			if(msg == null) {
				block();
				return;
			}
			
			// process the message
			String title = msg.getContent();
			ACLMessage reply = msg.createReply();
			
			PriceManager pm = (PriceManager) catalogue.get(title);
			
			// if we have the book
			if(pm != null) {
				// reply with a proposal
				reply.setPerformative(ACLMessage.PROPOSE);
				reply.setContent(String.valueOf(pm.getCurrentPrice()));
			// if we don't
			} else {
				// reply with a REFUSE
				reply.setPerformative(ACLMessage.REFUSE);				
			}
			
			// send the reply
			myAgent.send(reply);
		}
		
	}
	
	/**
	 * Serve incoming Accept Proposal CA's from buyer agents.
	 * 
	 * Receives an incoming ACCEPT PROPOSAL.
	 * If the proposal is corrupt,
	 * 	-> Reply with a NOT UNDERSTOOD.
	 * If the book is in the catalogue and the price >= what we're currently asking for:
	 * 	-> Reply with a CONFIRM.
	 * 	-> Stop the price manager.
	 * 	-> Take the book out of the catalogue.
	 * 	-> Notify the user that the book was sold for the proposed price.
	 * Otherwise,
	 * 	-> Reply with a DISCONFIRM.
	 * 
	 * @author peter
	 *
	 */
	private class PurchaseOrderServer extends CyclicBehaviour {
		private static final long serialVersionUID = 5093118259000491987L;

		/** What to tell the user when an item has been sold successfully. */
		private static final String SUCCESS_MSG = "Book %s has been sold for %d.";
		
		// we only care about Accept Proposal messages
		private MessageTemplate template =
				MessageTemplate.MatchPerformative(ACLMessage.ACCEPT_PROPOSAL);
		
		@Override
		public void action() {
			ACLMessage msg = myAgent.receive(template);
			
			// block until we have a [Accept Proposal] message
			if(msg == null) {
				block();
				return;
			}
			
			// in any case, we're going to reply to any accepted proposal
			ACLMessage reply = msg.createReply();
			
			try {
				// get the proposal which the buyer is accepting
				Proposal proposal = (Proposal) msg.getContentObject();
				// get the price manager for the concerned book
				PriceManager pm = (PriceManager) catalogue.get(proposal.getBookTitle());

				// if we still have the book and the proposal price is valid
				if(pm != null && pm.getCurrentPrice() <= proposal.getPrice()) {
					// reply with a confirmation
					reply.setPerformative(ACLMessage.CONFIRM);
					// stop the price manager for this book
					pm.stop();
					// remove the book from the catalogue
					catalogue.remove(proposal.getBookTitle());
					// notify the user of success
					gui.notifyUser(String.format(SUCCESS_MSG, proposal.getBookTitle(), proposal.getPrice()));
				// otherwise
				} else {
					// reply with a disconfirmation
					reply.setPerformative(ACLMessage.DISCONFIRM);				
				}
			// if we weren't sent back a proper proposal
			} catch (UnreadableException e) {
				// send a not understood reply we didn't get back a Proposal content
				reply.setPerformative(ACLMessage.NOT_UNDERSTOOD);
			}
			
			// finally, send the reply
			myAgent.send(reply);
		}
		
	}
}
