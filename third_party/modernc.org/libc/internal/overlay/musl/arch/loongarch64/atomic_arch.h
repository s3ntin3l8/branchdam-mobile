#ifndef __CCGO__

#define a_ll a_ll
static inline int a_ll(volatile int *p)
{
	int v;
	__asm__ __volatile__ (
		"ll.w %0, %1"
		: "=r"(v)
		: "ZC"(*p));
	return v;
}

#define a_sc a_sc
static inline int a_sc(volatile int *p, int v)
{
	int r;
	__asm__ __volatile__ (
		"sc.w %0, %1"
		: "=r"(r), "=ZC"(*p)
		: "0"(v) : "memory");
	return r;
}

#define a_ll_p a_ll_p
static inline void *a_ll_p(volatile void *p)
{
	void *v;
	__asm__ __volatile__ (
		"ll.d %0, %1"
		: "=r"(v)
		: "ZC"(*(void *volatile *)p));
	return v;
}

#define a_sc_p a_sc_p
static inline int a_sc_p(volatile void *p, void *v)
{
	long r;
	__asm__ __volatile__ (
		"sc.d %0, %1"
		: "=r"(r), "=ZC"(*(void *volatile *)p)
		: "0"(v)
		: "memory");
	return r;
}

#define a_barrier a_barrier
static inline void a_barrier()
{
	__asm__ __volatile__ ("dbar 0" : : : "memory");
}

#define a_pre_llsc  a_barrier
#define a_post_llsc a_barrier

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
