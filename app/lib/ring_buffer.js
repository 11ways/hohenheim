/**
 * A memory-efficient circular buffer for time-series data
 *
 * @constructor
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.7.0
 * @version  0.7.0
 *
 * @param    {Number}   capacity   The maximum number of items to store
 */
const RingBuffer = Function.inherits('Informer', 'Develry', function RingBuffer(capacity) {

	if (!capacity || capacity < 1) {
		throw new Error('RingBuffer capacity must be at least 1');
	}

	// The maximum number of items
	this.capacity = capacity;

	// The internal buffer array
	this.buffer = new Array(capacity);

	// The current head position (where next item will be written)
	this.head = 0;

	// The current number of items in the buffer
	this.length = 0;
});

/**
 * Push a new item into the buffer.
 * If the buffer is full, the oldest item is overwritten.
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.7.0
 * @version  0.7.0
 *
 * @param    {*}   item   The item to add
 *
 * @return   {RingBuffer}   Returns this for chaining
 */
RingBuffer.setMethod(function push(item) {

	// Store the item at the current head position
	this.buffer[this.head] = item;

	// Move head to next position, wrapping around if needed
	this.head = (this.head + 1) % this.capacity;

	// Increase length up to capacity
	if (this.length < this.capacity) {
		this.length++;
	}

	return this;
});

/**
 * Get all items as an array, in chronological order (oldest first).
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.7.0
 * @version  0.7.0
 *
 * @return   {Array}   Array of items in chronological order
 */
RingBuffer.setMethod(function toArray() {

	if (this.length === 0) {
		return [];
	}

	let result = new Array(this.length);

	if (this.length < this.capacity) {
		// Buffer not yet full, items are from index 0 to length-1
		for (let i = 0; i < this.length; i++) {
			result[i] = this.buffer[i];
		}
	} else {
		// Buffer is full, oldest item is at head position
		for (let i = 0; i < this.length; i++) {
			result[i] = this.buffer[(this.head + i) % this.capacity];
		}
	}

	return result;
});

/**
 * Get the last n items, in chronological order (oldest first).
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.7.0
 * @version  0.7.0
 *
 * @param    {Number}   n   The number of items to get
 *
 * @return   {Array}   Array of the last n items
 */
RingBuffer.setMethod(function getLast(n) {

	if (n <= 0 || this.length === 0) {
		return [];
	}

	// Limit n to the actual length
	n = Math.min(n, this.length);

	let result = new Array(n);

	// Calculate the starting position for the last n items
	// The most recent item is at (head - 1), so we need to go back n items from there
	let start;

	if (this.length < this.capacity) {
		// Buffer not full, items are sequential from 0
		start = this.length - n;
	} else {
		// Buffer is full, calculate position
		start = (this.head - n + this.capacity) % this.capacity;
	}

	for (let i = 0; i < n; i++) {
		if (this.length < this.capacity) {
			result[i] = this.buffer[start + i];
		} else {
			result[i] = this.buffer[(start + i) % this.capacity];
		}
	}

	return result;
});

/**
 * Peek at the most recent item without removing it.
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.7.0
 * @version  0.7.0
 *
 * @return   {*}   The most recent item, or undefined if empty
 */
RingBuffer.setMethod(function peek() {

	if (this.length === 0) {
		return undefined;
	}

	// Most recent item is at (head - 1), wrapping around
	let index = (this.head - 1 + this.capacity) % this.capacity;

	return this.buffer[index];
});

/**
 * Clear all items from the buffer.
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.7.0
 * @version  0.7.0
 *
 * @return   {RingBuffer}   Returns this for chaining
 */
RingBuffer.setMethod(function clear() {

	// Reset buffer to empty array of same capacity
	this.buffer = new Array(this.capacity);
	this.head = 0;
	this.length = 0;

	return this;
});
