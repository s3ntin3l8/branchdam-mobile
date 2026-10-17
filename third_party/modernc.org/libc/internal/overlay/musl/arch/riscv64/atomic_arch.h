#ifndef __CCGO__

#define a_barrier a_barrier
static inline void a_barrier()
{
	__asm__ __volatile__ ("fence rw,rw" : : : "memory");
}

#define a_cas a_cas
static inline int a_cas(volatile int *p, int t, int s)
{
	int old, tmp;
	__asm__ __volatile__ (
		"\n1:	lr.w.aqrl %0, (%2)\n"
		"	bne %0, %3, 1f\n"
		"	sc.w.aqrl %1, %4, (%2)\n"
		"	bnez %1, 1b\n"
		"1:"
		: "=&r"(old), "=&r"(tmp)
		: "r"(p), "r"((long)t), "r"((long)s)
		: "memory");
	return old;
}

#define a_cas_p a_cas_p
static inline void *a_cas_p(volatile void *p, void *t, void *s)
{
	void *old;
	int tmp;
	__asm__ __volatile__ (
		"\n1:	lr.d.aqrl %0, (%2)\n"
		"	bne %0, %3, 1f\n"
		"	sc.d.aqrl %1, %4, (%2)\n"
		"	bnez %1, 1b\n"
		"1:"
		: "=&r"(old), "=&r"(tmp)
		: "r"(p), "r"(t), "r"(s)
		: "memory");
	return old;
}

#else

/* ccgo cannot translate the inline assembly above. The primitives are only
 * declared here and implemented in Go, see atomic64.go in modernc.org/libc. */

#define a_cas a_cas
static inline int a_cas(volatile int *p, int t, int s);

#define a_cas_p a_cas_p
static inline void *a_cas_p(volatile void *p, void *t, void *s);

#define a_swap a_swap
static inline int a_swap(volatile int *p, int v);

#define a_fetch_add a_fetch_add
static inline int a_fetch_add(volatile int *p, int v);

#define a_and a_and
static inline void a_and(volatile int *p, int v);

#define a_or a_or
static inline void a_or(volatile int *p, int v);

#define a_and_64 a_and_64
static inline void a_and_64(volatile uint64_t *p, uint64_t v);

#define a_or_64 a_or_64
static inline void a_or_64(volatile uint64_t *p, uint64_t v);

#define a_store a_store
static inline void a_store(volatile int *p, int x);

#define a_barrier a_barrier
static inline void a_barrier(void);

#define a_spin a_spin
static inline void a_spin(void);

#define a_crash a_crash
static inline void a_crash(void);

#define a_ctz_64 a_ctz_64
static inline int a_ctz_64(uint64_t x);

#define a_clz_64 a_clz_64
static inline int a_clz_64(uint64_t x);

#endif
