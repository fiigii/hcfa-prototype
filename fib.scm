(define (fib n)
  (if (<= n 1)
      1
      (+ (fib (- n 1) (- n 2)))))

(define a (fib 2))
(define b (fib 10))
(define c (fib 5))


