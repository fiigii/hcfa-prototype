(define (even n)
  (if (= n 0)
      #t
      (odd (- n 1))))

(define (odd n)
  (if (= n 0)
      #f
      (even (- n 1))))

(define a (even 10))
(define b (even 0))
(define c (even 5))
